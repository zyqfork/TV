// GLES3 vs Vulkan benchmark, three workloads on identical scenes:
//   tris - many small triangles (draw-call / vertex bound)
//   fill - fullscreen quad overdraw, flat colour (fill-rate bound)
//   tex  - fullscreen quad sampling a 256x256 texture (sampling / bandwidth bound)
// Both backends use the same resolution, primitive counts, shader math and a hard
// per-frame sync (glFinish / submit+wait fence), so the numbers compare GPU+driver cost.
// Wall-clock only: this measures throughput, not latency or display presentation.
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <EGL/egl.h>
#include <GLES3/gl3.h>
#include <vulkan/vulkan.h>

static double now_ms(void) {
    struct timespec ts; clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec * 1000.0 + ts.tv_nsec / 1e6;
}

#define W 1280
#define H 720
#define TRI_FRAMES 200
#define TRIS_PER_FRAME 5000
#define FILL_FRAMES 100
static int g_fill_draws = 20;
#define TEX_FRAMES 100
#define TEX_DRAWS 20

/* ---------------- GLES ---------------- */
static const char *VS_SRC =
    "#version 300 es\n"
    "layout(location=0) in vec2 p;\n"
    "out vec2 uv;\n"
    "void main(){ uv=p*0.5+0.5; gl_Position=vec4(p,0.0,1.0); }\n";
static const char *FS_FLAT =
    "#version 300 es\n"
    "precision mediump float;\n"
    "out vec4 c;\n"
    "uniform vec4 u;\n"
    "void main(){ c=u; }\n";
static const char *FS_TEX =
    "#version 300 es\n"
    "precision mediump float;\n"
    "in vec2 uv;\n"
    "out vec4 c;\n"
    "uniform sampler2D t;\n"
    "void main(){ vec4 s=texture(t,uv*8.0); c=vec4(s.rgb*0.05,1.0); }\n";

static GLuint mk_shader(GLenum type, const char *src) {
    GLuint s = glCreateShader(type);
    glShaderSource(s, 1, &src, NULL);
    glCompileShader(s);
    GLint ok = 0; glGetShaderiv(s, GL_COMPILE_STATUS, &ok);
    if (!ok) { char log[512]; glGetShaderInfoLog(s, 512, NULL, log); printf("GLES shader: %s\n", log); }
    return s;
}

static GLuint mk_program(const char *vs, const char *fs) {
    GLuint p = glCreateProgram();
    glAttachShader(p, mk_shader(GL_VERTEX_SHADER, vs));
    glAttachShader(p, mk_shader(GL_FRAGMENT_SHADER, fs));
    glLinkProgram(p);
    GLint ok = 0; glGetProgramiv(p, GL_LINK_STATUS, &ok);
    if (!ok) { char log[512]; glGetProgramInfoLog(p, 512, NULL, log); printf("GLES link: %s\n", log); }
    return p;
}

static int run_gles(void) {
    EGLDisplay d = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (d == EGL_NO_DISPLAY || !eglInitialize(d, NULL, NULL)) { printf("GLES: no display\n"); return 1; }
    EGLint ca[] = { EGL_SURFACE_TYPE, EGL_PBUFFER_BIT, EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
                    EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8, EGL_NONE };
    EGLConfig cfg; EGLint n = 0;
    if (!eglChooseConfig(d, ca, &cfg, 1, &n) || n < 1) { printf("GLES: no config\n"); return 1; }
    EGLint pb[] = { EGL_WIDTH, W, EGL_HEIGHT, H, EGL_NONE };
    EGLSurface surf = eglCreatePbufferSurface(d, cfg, pb);
    eglBindAPI(EGL_OPENGL_ES_API);
    EGLint xa[] = { EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE };
    EGLContext ctx = eglCreateContext(d, cfg, EGL_NO_CONTEXT, xa);
    if (ctx == EGL_NO_CONTEXT) { printf("GLES: no context\n"); return 1; }
    eglMakeCurrent(d, surf, surf, ctx);
    printf("GLES_DEVICE renderer=\"%s\" version=\"%s\"\n", glGetString(GL_RENDERER), glGetString(GL_VERSION));
    glViewport(0, 0, W, H);
    glDisable(GL_DEPTH_TEST);
    glDisable(GL_CULL_FACE);

    /* Render into an FBO with a GL_RGBA8 texture attachment, matching the Vulkan
       VK_FORMAT_R8G8B8A8_UNORM optimal-tiled image. A pbuffer target is usually
       linear, which would penalise GLES on a tiling GPU and inflate the VK gap. */
    GLuint fboTex, fbo;
    glGenTextures(1, &fboTex);
    glBindTexture(GL_TEXTURE_2D, fboTex);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, W, H, 0, GL_RGBA, GL_UNSIGNED_BYTE, NULL);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glGenFramebuffers(1, &fbo);
    glBindFramebuffer(GL_FRAMEBUFFER, fbo);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, fboTex, 0);
    if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
        puts("GLES: FBO incomplete, using pbuffer target");
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }
    glBindTexture(GL_TEXTURE_2D, 0);

    unsigned char *px = malloc(256 * 256 * 4);
    for (int i = 0; i < 256 * 256; i++) {
        px[i * 4 + 0] = i & 0xff; px[i * 4 + 1] = (i >> 3) & 0xff;
        px[i * 4 + 2] = (i >> 7) & 0xff; px[i * 4 + 3] = 255;
    }
    GLuint tex; glGenTextures(1, &tex); glBindTexture(GL_TEXTURE_2D, tex);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, 256, 256, 0, GL_RGBA, GL_UNSIGNED_BYTE, px);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_REPEAT);
    glBindTexture(GL_TEXTURE_2D, 0);
    free(px);

    static const float quad[] = { -1,-1, 1,-1, -1,1, 1,1 };
    static const float tri[]  = { -0.02f,-0.02f, 0.02f,-0.02f, 0.0f,0.02f };
    GLuint qvbo, tvbo;
    glGenBuffers(1, &qvbo); glBindBuffer(GL_ARRAY_BUFFER, qvbo);
    glBufferData(GL_ARRAY_BUFFER, sizeof(quad), quad, GL_STATIC_DRAW);
    glGenBuffers(1, &tvbo); glBindBuffer(GL_ARRAY_BUFFER, tvbo);
    glBufferData(GL_ARRAY_BUFFER, sizeof(tri), tri, GL_STATIC_DRAW);
    glEnableVertexAttribArray(0);

    /* tris */
    GLuint pflat = mk_program(VS_SRC, FS_FLAT);
    glUseProgram(pflat);
    glUniform4f(glGetUniformLocation(pflat, "u"), 0.2f, 0.6f, 0.9f, 1.0f);
    glBindBuffer(GL_ARRAY_BUFFER, tvbo);
    glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 0, 0);
    for (int f = 0; f < 10; f++) {
        glClear(GL_COLOR_BUFFER_BIT);
        for (int i = 0; i < TRIS_PER_FRAME; i++) glDrawArrays(GL_TRIANGLES, 0, 3);
        glFinish();
    }
    double t0 = now_ms();
    for (int f = 0; f < TRI_FRAMES; f++) {
        glClearColor(0.05f, 0.05f, 0.1f, 1.0f);
        glClear(GL_COLOR_BUFFER_BIT);
        for (int i = 0; i < TRIS_PER_FRAME; i++) glDrawArrays(GL_TRIANGLES, 0, 3);
        glFinish();
    }
    double ms = now_ms() - t0;
    printf("GLES_TRIS frames=%d tris_per_frame=%d ms=%.1f fps=%.1f tris_per_sec=%.0f\n",
           TRI_FRAMES, TRIS_PER_FRAME, ms, TRI_FRAMES * 1000.0 / ms,
           (double)TRI_FRAMES * TRIS_PER_FRAME * 1000.0 / ms);

    /* fill: fullscreen quad overdraw, flat colour */
    glBindBuffer(GL_ARRAY_BUFFER, qvbo);
    glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 0, 0);
    for (int f = 0; f < 5; f++) {
        glClear(GL_COLOR_BUFFER_BIT);
        for (int i = 0; i < g_fill_draws; i++) glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
        glFinish();
    }
    t0 = now_ms();
    for (int f = 0; f < FILL_FRAMES; f++) {
        glClear(GL_COLOR_BUFFER_BIT);
        for (int i = 0; i < g_fill_draws; i++) glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
        glFinish();
    }
    ms = now_ms() - t0;
    double gpx = (double)FILL_FRAMES * g_fill_draws * W * H / 1e9;
    printf("GLES_FILL frames=%d draws_per_frame=%d ms=%.1f fps=%.1f gpx_per_sec=%.2f\n",
           FILL_FRAMES, g_fill_draws, ms, FILL_FRAMES * 1000.0 / ms, gpx * 1000.0 / ms);

    /* tex: fullscreen quad sampling */
    GLuint ptex = mk_program(VS_SRC, FS_TEX);
    glUseProgram(ptex);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, tex);
    glUniform1i(glGetUniformLocation(ptex, "t"), 0);
    for (int f = 0; f < 5; f++) {
        glClear(GL_COLOR_BUFFER_BIT);
        for (int i = 0; i < TEX_DRAWS; i++) glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
        glFinish();
    }
    t0 = now_ms();
    for (int f = 0; f < TEX_FRAMES; f++) {
        glClear(GL_COLOR_BUFFER_BIT);
        for (int i = 0; i < TEX_DRAWS; i++) glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
        glFinish();
    }
    ms = now_ms() - t0;
    gpx = (double)TEX_FRAMES * TEX_DRAWS * W * H / 1e9;
    printf("GLES_TEX frames=%d draws_per_frame=%d ms=%.1f fps=%.1f gpx_per_sec=%.2f\n",
           TEX_FRAMES, TEX_DRAWS, ms, TEX_FRAMES * 1000.0 / ms, gpx * 1000.0 / ms);
    return 0;
}

/* ---------------- Vulkan ----------------
 Build notes: the *_spv.inc arrays are generated from GLSL 310 es sources with
   glslangValidator -V <stage> -o <stage>.spv
 and then dumped as uint32 words. Both backends must use the same .inc so the
 shading work stays identical. */
static const uint32_t VS_SPV[] = {
#include "vs3_spv.inc"
};
static const uint32_t FS_TEX_SPV[] = {
#include "fs3_spv.inc"
};
static const uint32_t FS_FLAT_SPV[] = {
#include "fsflat_spv.inc"
};

static VkPhysicalDevice g_pd;
static VkDevice g_dev;
static VkQueue g_q;
static VkRenderPass g_rp;
static VkFramebuffer g_fb;
static VkPipelineLayout g_pl;
static VkCommandPool g_pool;
static VkCommandBuffer g_cb;
static VkFence g_fence;
static VkDescriptorSet g_ds;

static uint32_t pick_mem(VkPhysicalDevice pd, uint32_t bits, VkMemoryPropertyFlags want) {
    VkPhysicalDeviceMemoryProperties mp; vkGetPhysicalDeviceMemoryProperties(pd, &mp);
    for (uint32_t i = 0; i < mp.memoryTypeCount; i++)
        if ((bits & (1u << i)) && (mp.memoryTypes[i].propertyFlags & want) == want) return i;
    return 0;
}

static VkShaderModule mk_spv(const uint32_t *code, size_t bytes) {
    VkShaderModuleCreateInfo ci = {0};
    ci.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
    ci.codeSize = bytes; ci.pCode = code;
    VkShaderModule m;
    if (vkCreateShaderModule(g_dev, &ci, NULL, &m) != VK_SUCCESS) { printf("VK: shader fail\n"); exit(1); }
    return m;
}

static VkPipeline mk_pipe(VkShaderModule vs, VkShaderModule fs, VkPrimitiveTopology topo) {
    VkPipelineShaderStageCreateInfo st[2] = {{0}};
    st[0].sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    st[0].stage = VK_SHADER_STAGE_VERTEX_BIT; st[0].module = vs; st[0].pName = "main";
    st[1].sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    st[1].stage = VK_SHADER_STAGE_FRAGMENT_BIT; st[1].module = fs; st[1].pName = "main";

    VkVertexInputBindingDescription vb = {0, 8, VK_VERTEX_INPUT_RATE_VERTEX};
    VkVertexInputAttributeDescription va = {0, 0, VK_FORMAT_R32G32_SFLOAT, 0};
    VkPipelineVertexInputStateCreateInfo vi = {0};
    vi.sType = VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO;
    vi.vertexBindingDescriptionCount = 1; vi.pVertexBindingDescriptions = &vb;
    vi.vertexAttributeDescriptionCount = 1; vi.pVertexAttributeDescriptions = &va;

    VkPipelineInputAssemblyStateCreateInfo ia = {0};
    ia.sType = VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO;
    ia.topology = topo;

    VkViewport vp = {0, 0, W, H, 0, 1};
    VkRect2D sc = {{0, 0}, {W, H}};
    VkPipelineViewportStateCreateInfo vps = {0};
    vps.sType = VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO;
    vps.viewportCount = 1; vps.pViewports = &vp; vps.scissorCount = 1; vps.pScissors = &sc;

    VkPipelineRasterizationStateCreateInfo rs = {0};
    rs.sType = VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO;
    rs.polygonMode = VK_POLYGON_MODE_FILL; rs.cullMode = VK_CULL_MODE_NONE;
    rs.frontFace = VK_FRONT_FACE_COUNTER_CLOCKWISE; rs.lineWidth = 1.0f;

    VkPipelineMultisampleStateCreateInfo ms = {0};
    ms.sType = VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO;
    ms.rasterizationSamples = VK_SAMPLE_COUNT_1_BIT;

    VkPipelineColorBlendAttachmentState cba = {0};
    cba.colorWriteMask = 0xF;
    VkPipelineColorBlendStateCreateInfo cbs = {0};
    cbs.sType = VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO;
    cbs.attachmentCount = 1; cbs.pAttachments = &cba;

    VkGraphicsPipelineCreateInfo gp = {0};
    gp.sType = VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO;
    gp.stageCount = 2; gp.pStages = st;
    gp.pVertexInputState = &vi; gp.pInputAssemblyState = &ia;
    gp.pViewportState = &vps; gp.pRasterizationState = &rs;
    gp.pMultisampleState = &ms; gp.pColorBlendState = &cbs;
    gp.layout = g_pl; gp.renderPass = g_rp; gp.subpass = 0;

    VkPipeline p;
    VkResult r = vkCreateGraphicsPipelines(g_dev, VK_NULL_HANDLE, 1, &gp, NULL, &p);
    if (r != VK_SUCCESS) { printf("VK: pipeline fail %d\n", r); exit(1); }
    return p;
}

static VkBuffer g_vbo_tri, g_vbo_quad;
static VkDeviceSize g_off = 0;

static void upload_buffer(const float *data, size_t bytes, VkBuffer *out) {
    VkBufferCreateInfo bc = {0};
    bc.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
    bc.size = bytes; bc.usage = VK_BUFFER_USAGE_VERTEX_BUFFER_BIT;
    vkCreateBuffer(g_dev, &bc, NULL, out);
    VkMemoryRequirements mr; vkGetBufferMemoryRequirements(g_dev, *out, &mr);
    VkMemoryAllocateInfo ma = {0};
    ma.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    ma.allocationSize = mr.size;
    ma.memoryTypeIndex = pick_mem(g_pd, mr.memoryTypeBits,
        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
    VkDeviceMemory mem; vkAllocateMemory(g_dev, &ma, NULL, &mem);
    void *p; vkMapMemory(g_dev, mem, 0, bytes, 0, &p); memcpy(p, data, bytes); vkUnmapMemory(g_dev, mem);
    vkBindBufferMemory(g_dev, *out, mem, 0);
}

static void run_batch(VkPipeline pipe, VkBuffer vbo, int frames, int draws, int verts, const char *label, int textured) {
    for (int pass = 0; pass < 2; pass++) {
        int n = (pass == 0) ? 5 : frames;
        double t0 = now_ms();
        for (int f = 0; f < n; f++) {
            vkResetCommandBuffer(g_cb, 0);
            VkCommandBufferBeginInfo bi = {0};
            bi.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
            vkBeginCommandBuffer(g_cb, &bi);
            VkClearValue cv; memset(&cv, 0, sizeof(cv));
            cv.color.float32[0] = 0.05f; cv.color.float32[1] = 0.05f;
            cv.color.float32[2] = 0.1f;  cv.color.float32[3] = 1.0f;
            VkRenderPassBeginInfo rbi = {0};
            rbi.sType = VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO;
            rbi.renderPass = g_rp; rbi.framebuffer = g_fb;
            rbi.renderArea.extent.width = W; rbi.renderArea.extent.height = H;
            rbi.clearValueCount = 1; rbi.pClearValues = &cv;
            vkCmdBeginRenderPass(g_cb, &rbi, VK_SUBPASS_CONTENTS_INLINE);
            vkCmdBindPipeline(g_cb, VK_PIPELINE_BIND_POINT_GRAPHICS, pipe);
            if (textured) vkCmdBindDescriptorSets(g_cb, VK_PIPELINE_BIND_POINT_GRAPHICS, g_pl, 0, 1, &g_ds, 0, NULL);
            vkCmdBindVertexBuffers(g_cb, 0, 1, &vbo, &g_off);
            for (int i = 0; i < draws; i++) vkCmdDraw(g_cb, (uint32_t)verts, 1, 0, 0);
            vkCmdEndRenderPass(g_cb);
            vkEndCommandBuffer(g_cb);
            VkSubmitInfo si = {0};
            si.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
            si.commandBufferCount = 1; si.pCommandBuffers = &g_cb;
            vkResetFences(g_dev, 1, &g_fence);
            vkQueueSubmit(g_q, 1, &si, g_fence);
            vkWaitForFences(g_dev, 1, &g_fence, VK_TRUE, UINT64_MAX);
        }
        double ms = now_ms() - t0;
        if (pass == 1) {
            double gpx = (double)frames * draws * W * H / 1e9;
            printf("VK_%s frames=%d draws_per_frame=%d ms=%.1f fps=%.1f gpx_per_sec=%.2f\n",
                   label, frames, draws, ms, frames * 1000.0 / ms, gpx * 1000.0 / ms);
        }
    }
}

static int run_vk(void) {
    VkApplicationInfo app = {0};
    app.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    app.pApplicationName = "aic-bench"; app.apiVersion = VK_API_VERSION_1_1;
    VkInstanceCreateInfo ici = {0};
    ici.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO; ici.pApplicationInfo = &app;
    VkInstance inst;
    if (vkCreateInstance(&ici, NULL, &inst) != VK_SUCCESS) { printf("VK: no instance\n"); return 1; }
    uint32_t nd = 0; vkEnumeratePhysicalDevices(inst, &nd, NULL);
    if (nd == 0) { printf("VK: no device\n"); return 1; }
    VkPhysicalDevice *pds = calloc(nd, sizeof(*pds));
    vkEnumeratePhysicalDevices(inst, &nd, pds);
    g_pd = pds[0];
    VkPhysicalDeviceProperties prop; vkGetPhysicalDeviceProperties(g_pd, &prop);
    printf("VK_DEVICE name=\"%s\" api=%u.%u.%u driver=%u\n", prop.deviceName,
           VK_VERSION_MAJOR(prop.apiVersion), VK_VERSION_MINOR(prop.apiVersion),
           VK_VERSION_PATCH(prop.apiVersion), prop.driverVersion);

    uint32_t nq = 0; vkGetPhysicalDeviceQueueFamilyProperties(g_pd, &nq, NULL);
    VkQueueFamilyProperties *qf = calloc(nq, sizeof(*qf));
    vkGetPhysicalDeviceQueueFamilyProperties(g_pd, &nq, qf);
    int qi = -1;
    for (uint32_t i = 0; i < nq; i++) if (qf[i].queueFlags & VK_QUEUE_GRAPHICS_BIT) { qi = (int)i; break; }
    if (qi < 0) { printf("VK: no gfx queue\n"); return 1; }

    float prio = 1.0f;
    VkDeviceQueueCreateInfo qci = {0};
    qci.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
    qci.queueFamilyIndex = (uint32_t)qi; qci.queueCount = 1; qci.pQueuePriorities = &prio;
    VkDeviceCreateInfo dci = {0};
    dci.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
    dci.queueCreateInfoCount = 1; dci.pQueueCreateInfos = &qci;
    if (vkCreateDevice(g_pd, &dci, NULL, &g_dev) != VK_SUCCESS) { printf("VK: no device obj\n"); return 1; }
    vkGetDeviceQueue(g_dev, (uint32_t)qi, 0, &g_q);

    VkImageCreateInfo img = {0};
    img.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
    img.imageType = VK_IMAGE_TYPE_2D; img.format = VK_FORMAT_R8G8B8A8_UNORM;
    img.extent.width = W; img.extent.height = H; img.extent.depth = 1;
    img.mipLevels = 1; img.arrayLayers = 1; img.samples = 1;
    img.tiling = VK_IMAGE_TILING_OPTIMAL;
    img.usage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
    img.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    VkImage color;
    if (vkCreateImage(g_dev, &img, NULL, &color) != VK_SUCCESS) { printf("VK: no image\n"); return 1; }
    VkMemoryRequirements mr; vkGetImageMemoryRequirements(g_dev, color, &mr);
    VkMemoryAllocateInfo ma = {0};
    ma.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    ma.allocationSize = mr.size;
    ma.memoryTypeIndex = pick_mem(g_pd, mr.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    VkDeviceMemory mem; vkAllocateMemory(g_dev, &ma, NULL, &mem);
    vkBindImageMemory(g_dev, color, mem, 0);

    VkImageViewCreateInfo vci = {0};
    vci.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
    vci.image = color; vci.viewType = VK_IMAGE_VIEW_TYPE_2D; vci.format = VK_FORMAT_R8G8B8A8_UNORM;
    VkImageSubresourceRange rng = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    vci.subresourceRange = rng;
    VkImageView view; vkCreateImageView(g_dev, &vci, NULL, &view);

    VkAttachmentDescription att = {0};
    att.format = VK_FORMAT_R8G8B8A8_UNORM; att.samples = 1;
    att.loadOp = VK_ATTACHMENT_LOAD_OP_CLEAR; att.storeOp = VK_ATTACHMENT_STORE_OP_STORE;
    att.stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
    att.stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
    att.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    att.finalLayout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
    VkAttachmentReference ar = {0, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL};
    VkSubpassDescription sp = {0};
    sp.pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS;
    sp.colorAttachmentCount = 1; sp.pColorAttachments = &ar;
    VkRenderPassCreateInfo rpci = {0};
    rpci.sType = VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO;
    rpci.attachmentCount = 1; rpci.pAttachments = &att;
    rpci.subpassCount = 1; rpci.pSubpasses = &sp;
    vkCreateRenderPass(g_dev, &rpci, NULL, &g_rp);

    VkImageView fbatt[] = { view };
    VkFramebufferCreateInfo fci = {0};
    fci.sType = VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO;
    fci.renderPass = g_rp; fci.attachmentCount = 1; fci.pAttachments = fbatt;
    fci.width = W; fci.height = H; fci.layers = 1;
    vkCreateFramebuffer(g_dev, &fci, NULL, &g_fb);

    /* texture image + sampler + descriptor */
    const int TS = 256;
    unsigned char *px = malloc(TS * TS * 4);
    for (int i = 0; i < TS * TS; i++) {
        px[i * 4 + 0] = i & 0xff; px[i * 4 + 1] = (i >> 3) & 0xff;
        px[i * 4 + 2] = (i >> 7) & 0xff; px[i * 4 + 3] = 255;
    }
    VkBufferCreateInfo sbc = {0};
    sbc.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
    sbc.size = (VkDeviceSize)TS * TS * 4; sbc.usage = VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
    VkBuffer stage; vkCreateBuffer(g_dev, &sbc, NULL, &stage);
    VkMemoryRequirements smr; vkGetBufferMemoryRequirements(g_dev, stage, &smr);
    VkMemoryAllocateInfo sma = {0};
    sma.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    sma.allocationSize = smr.size;
    sma.memoryTypeIndex = pick_mem(g_pd, smr.memoryTypeBits,
        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
    VkDeviceMemory smem; vkAllocateMemory(g_dev, &sma, NULL, &smem);
    void *sp2; vkMapMemory(g_dev, smem, 0, sbc.size, 0, &sp2); memcpy(sp2, px, sbc.size); vkUnmapMemory(g_dev, smem);
    vkBindBufferMemory(g_dev, stage, smem, 0);
    free(px);

    VkImageCreateInfo tic = {0};
    tic.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
    tic.imageType = VK_IMAGE_TYPE_2D; tic.format = VK_FORMAT_R8G8B8A8_UNORM;
    tic.extent.width = TS; tic.extent.height = TS; tic.extent.depth = 1;
    tic.mipLevels = 1; tic.arrayLayers = 1; tic.samples = 1;
    tic.tiling = VK_IMAGE_TILING_OPTIMAL;
    tic.usage = VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
    tic.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    VkImage timg; vkCreateImage(g_dev, &tic, NULL, &timg);
    VkMemoryRequirements tmr; vkGetImageMemoryRequirements(g_dev, timg, &tmr);
    VkMemoryAllocateInfo tma = {0};
    tma.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    tma.allocationSize = tmr.size;
    tma.memoryTypeIndex = pick_mem(g_pd, tmr.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    VkDeviceMemory tmem; vkAllocateMemory(g_dev, &tma, NULL, &tmem);
    vkBindImageMemory(g_dev, timg, tmem, 0);

    VkCommandPoolCreateInfo cpci = {0};
    cpci.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
    cpci.queueFamilyIndex = (uint32_t)qi;
    cpci.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
    vkCreateCommandPool(g_dev, &cpci, NULL, &g_pool);
    VkCommandBufferAllocateInfo cbai = {0};
    cbai.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    cbai.commandPool = g_pool; cbai.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY; cbai.commandBufferCount = 1;
    vkAllocateCommandBuffers(g_dev, &cbai, &g_cb);
    VkFenceCreateInfo fci2 = {0}; fci2.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
    vkCreateFence(g_dev, &fci2, NULL, &g_fence);

    /* upload texture: UNDEFINED -> TRANSFER_DST -> SHADER_READ_ONLY */
    VkCommandBufferBeginInfo bi = {0};
    bi.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    vkBeginCommandBuffer(g_cb, &bi);
    VkImageMemoryBarrier b1 = {0};
    b1.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    b1.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    b1.newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
    b1.srcQueueFamilyIndex = b1.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    b1.image = timg;
    b1.subresourceRange = (VkImageSubresourceRange){VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    b1.srcAccessMask = 0; b1.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    vkCmdPipelineBarrier(g_cb, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                         0, 0, NULL, 0, NULL, 1, &b1);
    VkBufferImageCopy bic = {0};
    bic.imageSubresource = (VkImageSubresourceLayers){VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
    bic.imageExtent = (VkExtent3D){TS, TS, 1};
    vkCmdCopyBufferToImage(g_cb, stage, timg, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &bic);
    VkImageMemoryBarrier b2 = b1;
    b2.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
    b2.newLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    b2.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    b2.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    vkCmdPipelineBarrier(g_cb, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                         0, 0, NULL, 0, NULL, 1, &b2);
    vkEndCommandBuffer(g_cb);
    VkSubmitInfo si0 = {0};
    si0.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    si0.commandBufferCount = 1; si0.pCommandBuffers = &g_cb;
    vkQueueSubmit(g_q, 1, &si0, g_fence);
    vkWaitForFences(g_dev, 1, &g_fence, VK_TRUE, UINT64_MAX);

    VkImageViewCreateInfo tvi = {0};
    tvi.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
    tvi.image = timg; tvi.viewType = VK_IMAGE_VIEW_TYPE_2D; tvi.format = VK_FORMAT_R8G8B8A8_UNORM;
    tvi.subresourceRange = rng;
    VkImageView tview; vkCreateImageView(g_dev, &tvi, NULL, &tview);

    VkSamplerCreateInfo sci = {0};
    sci.sType = VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO;
    sci.magFilter = VK_FILTER_LINEAR; sci.minFilter = VK_FILTER_LINEAR;
    sci.addressModeU = VK_SAMPLER_ADDRESS_MODE_REPEAT;
    sci.addressModeV = VK_SAMPLER_ADDRESS_MODE_REPEAT;
    sci.addressModeW = VK_SAMPLER_ADDRESS_MODE_REPEAT;
    sci.maxLod = 0.0f;
    VkSampler smp; vkCreateSampler(g_dev, &sci, NULL, &smp);

    VkDescriptorSetLayoutBinding dslb = {0};
    dslb.binding = 0; dslb.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    dslb.descriptorCount = 1; dslb.stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT;
    VkDescriptorSetLayoutCreateInfo dslci = {0};
    dslci.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
    dslci.bindingCount = 1; dslci.pBindings = &dslb;
    VkDescriptorSetLayout dsl; vkCreateDescriptorSetLayout(g_dev, &dslci, NULL, &dsl);

    VkDescriptorPoolSize dps = {VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1};
    VkDescriptorPoolCreateInfo dpci = {0};
    dpci.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
    dpci.maxSets = 1; dpci.poolSizeCount = 1; dpci.pPoolSizes = &dps;
    VkDescriptorPool dp; vkCreateDescriptorPool(g_dev, &dpci, NULL, &dp);
    VkDescriptorSetAllocateInfo dsai = {0};
    dsai.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
    dsai.descriptorPool = dp; dsai.descriptorSetCount = 1; dsai.pSetLayouts = &dsl;
    vkAllocateDescriptorSets(g_dev, &dsai, &g_ds);
    VkDescriptorImageInfo dii = {smp, tview, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL};
    VkWriteDescriptorSet wds = {0};
    wds.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    wds.dstSet = g_ds; wds.dstBinding = 0; wds.descriptorCount = 1;
    wds.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    wds.pImageInfo = &dii;
    vkUpdateDescriptorSets(g_dev, 1, &wds, 0, NULL);

    VkPipelineLayoutCreateInfo plci = {0};
    plci.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
    plci.setLayoutCount = 1; plci.pSetLayouts = &dsl;
    vkCreatePipelineLayout(g_dev, &plci, NULL, &g_pl);

    static const float quad[] = { -1,-1, 1,-1, -1,1, 1,1 };
    static const float tri[]  = { -0.02f,-0.02f, 0.02f,-0.02f, 0.0f,0.02f };
    upload_buffer(quad, sizeof(quad), &g_vbo_quad);
    upload_buffer(tri, sizeof(tri), &g_vbo_tri);

    VkShaderModule vs = mk_spv(VS_SPV, sizeof(VS_SPV));
    VkShaderModule fsFlat = mk_spv(FS_FLAT_SPV, sizeof(FS_FLAT_SPV));
    VkShaderModule fsTex = mk_spv(FS_TEX_SPV, sizeof(FS_TEX_SPV));
    VkPipeline pTri = mk_pipe(vs, fsFlat, VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST);
    VkPipeline pFill = mk_pipe(vs, fsFlat, VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP);
    VkPipeline pTex = mk_pipe(vs, fsTex, VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP);

    /* Same primitive counts and topology as the GLES side: 3-vertex triangles,
       4-vertex fullscreen strips, identical draw counts and per-frame sync. */
    run_batch(pTri, g_vbo_tri, TRI_FRAMES, TRIS_PER_FRAME, 3, "TRIS", 0);
    run_batch(pFill, g_vbo_quad, FILL_FRAMES, g_fill_draws, 4, "FILL", 0);
    run_batch(pTex, g_vbo_quad, TEX_FRAMES, TEX_DRAWS, 4, "TEX", 1);
    return 0;
}

int main(int argc, char **argv) {
    const char *mode = argc > 1 ? argv[1] : "both";
    const char *fillEnv = getenv("FILL_DRAWS");
    if (fillEnv) { int v = atoi(fillEnv); if (v > 0) g_fill_draws = v; }
    if (!strcmp(mode, "gles") || !strcmp(mode, "both")) run_gles();
    if (!strcmp(mode, "vk") || !strcmp(mode, "both")) run_vk();
    return 0;
}
