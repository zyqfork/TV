#!/usr/bin/env python3
"""Apply only async command/reply and post-open cancellation in an OWNED native tree.

The filename is retained for Gradle's pinned native-input contract. The old
subtitle bitmap/JNI and VO redraw patches are intentionally no longer installed:
automatic direct output is restricted to subtitle-free live playback, and mpv's
normal GPU/libass path handles every selected subtitle.
"""
import argparse
import shutil
from pathlib import Path

p = argparse.ArgumentParser()
p.add_argument('--root', type=Path, required=True)
p.add_argument('--native', type=Path, required=True)
a = p.parse_args()
mpv = a.root / 'buildscripts/deps/mpv'
ffmpeg = a.root / 'buildscripts/deps/ffmpeg'
jni = a.root / 'app/src/main/jni'

def patch(path, old, new, marker):
    value = path.read_text()
    if marker in value:
        return
    if value.count(old) != 1:
        raise RuntimeError(f'Pinned native patch mismatch: {path}: {old[:80]}')
    path.write_text(value.replace(old, new), newline='\n')

for name in ('android_command_jni.cpp', 'android_command_jni.h'):
    shutil.copyfile(a.native / name, jni / name)
patch(jni / 'event.cpp', '#include "log.h"',
      '#include "log.h"\n#include "android_command_jni.h"', 'android_command_jni.h')
patch(jni / 'event.cpp', '        case MPV_EVENT_PROPERTY_CHANGE:',
      '''        case MPV_EVENT_SET_PROPERTY_REPLY:
        case MPV_EVENT_COMMAND_REPLY:
            android_command_reply(env, mp_event);
            break;
        case MPV_EVENT_PROPERTY_CHANGE:''', 'case MPV_EVENT_COMMAND_REPLY:')
# Upgrade already-patched owned trees as well as fresh ones.
patch(jni / 'event.cpp', '        case MPV_EVENT_COMMAND_REPLY:',
      '        case MPV_EVENT_SET_PROPERTY_REPLY:\n        case MPV_EVENT_COMMAND_REPLY:',
      'case MPV_EVENT_SET_PROPERTY_REPLY:')
# sub-add temporarily unlocks the core while opening a URL: cancellation must
# still prevent a completed old request from publishing into the replacement file.
patch(mpv / 'player/loadfile.c',
      '''    // The command could have overlapped with playback exiting. (We don't care
    // if playback has started again meanwhile - weird, but not a problem.)
    if (mpctx->stop_play)
        goto err_out;''',
      '''    // FONGMI_SUBTITLE_ABORT_PUBLISH: cancellation also applies after demux open.
    if (mpctx->stop_play || mp_cancel_test(cancel))
        goto err_out;''', 'FONGMI_SUBTITLE_ABORT_PUBLISH')
patch(jni / 'Android.mk', '\tthumbnail.cpp',
      '\tthumbnail.cpp \\\n\tandroid_command_jni.cpp', 'android_command_jni.cpp')
# Expose the VO's actual current frame timestamp, not audio-led time-pos or an
# unchanged width/height. This is a native VO-frame signal, not a physical display measurement.
patch(mpv / 'player/command.c',
      '        {"picture-type",    SUB_PROP_STR(pict_type), .unavailable = !pict_type},',
      '''        // FONGMI_VO_FRAME_PTS: force-window placeholders are NOT decoded video frames.
        {"pts", SUB_PROP_DOUBLE(f->pts),
            .unavailable = f->params.force_window || f->pts == MP_NOPTS_VALUE},
        {"picture-type",    SUB_PROP_STR(pict_type), .unavailable = !pict_type},''',
      'FONGMI_VO_FRAME_PTS')

# Read-only format diagnostics for the GPU's zero-copy ImageReader path. Do not guess
# stride/crop offsets or discard source pixels to hide a hardware-only edge artifact.
image_reader = mpv / 'video/out/hwdec/hwdec_aimagereader.c'
# A timed-out map used to return success after unmap had already destroyed the EGLImage.
# Sampling a destroyed texture is invalid. This explains a possible visual artifact,
# not by itself the later MediaCodec timeout; buffer/fence ownership must be measured.
patch(image_reader,
      '''static void mapper_uninit(struct ra_hwdec_mapper *mapper)
{
    struct priv *p = mapper->priv;
    struct priv_owner *o = mapper->owner->priv;
    GL *gl = ra_gl_get(mapper->ra);

    o->AImageReader_setImageListener(o->reader, NULL);''',
      '''static int dup_release_fence(struct ra_hwdec_mapper *mapper)
{
    struct priv *p = mapper->priv;
    GL *gl = ra_gl_get(mapper->ra);
    EGLDisplay dpy = eglGetCurrentDisplay();
    if (!p->native_fence || dpy == EGL_NO_DISPLAY)
        return -1;
    const EGLint attribs[] = {
        0x3145 /* EGL_SYNC_NATIVE_FENCE_FD_ANDROID */, -1 /* EGL_NO_NATIVE_FENCE_FD_ANDROID */,
        EGL_NONE,
    };
    void *sync = p->CreateSyncKHR(dpy, 0x3144 /* EGL_SYNC_NATIVE_FENCE_ANDROID */, attribs);
    if (!sync)
        return -1;
    // The fd only exists once the fence command has been flushed to the GPU.
    gl->Flush();
    int fd = p->DupNativeFenceFDANDROID(dpy, sync);
    p->DestroySyncKHR(dpy, sync);
    return fd >= 0 ? fd : -1;
}

static void release_displayed_image(struct ra_hwdec_mapper *mapper)
{
    struct priv *p = mapper->priv;
    struct priv_owner *o = mapper->owner->priv;
    if (!p->image && !p->egl_image)
        return;
    // FONGMI_IMAGE_LIFETIME: every GL command that samples this buffer has been
    // submitted. Return it with a GPU release fence: the codec waits on the fence
    // before writing it again, so neither a CPU wait nor an early reuse (green) occurs.
    // Without native fences, glFinish is the only correct way to know the GPU is done.
    GL *gl = ra_gl_get(mapper->ra);
    int fence = -1;
    if (p->image && o->AImage_deleteAsync)
        fence = dup_release_fence(mapper);
    if (fence < 0)
        gl->Finish();
    if (p->egl_image) {
        p->DestroyImageKHR(eglGetCurrentDisplay(), p->egl_image);
        p->egl_image = 0;
    }
    if (p->image) {
        if (fence >= 0)
            o->AImage_deleteAsync(p->image, fence);
        else
            o->AImage_delete(p->image);
        p->image = NULL;
    }
}

static void mapper_uninit(struct ra_hwdec_mapper *mapper)
{
    struct priv *p = mapper->priv;
    struct priv_owner *o = mapper->owner->priv;
    GL *gl = ra_gl_get(mapper->ra);

    o->AImageReader_setImageListener(o->reader, NULL);
    release_displayed_image(mapper);''',
      'FONGMI_IMAGE_LIFETIME')
patch(image_reader,
      '''static void mapper_unmap(struct ra_hwdec_mapper *mapper)
{
    struct priv *p = mapper->priv;
    struct priv_owner *o = mapper->owner->priv;

    if (p->egl_image) {
        p->DestroyImageKHR(eglGetCurrentDisplay(), p->egl_image);
        p->egl_image = 0;
    }

    if (p->image) {
        o->AImage_delete(p->image);
        p->image = NULL;
    }
}''',
      '''static void mapper_unmap(struct ra_hwdec_mapper *mapper)
{
    // FONGMI_IMAGE_UNMAP_HOLDS: the displayed image stays until a newer acquire
    // replaces it or the mapper is destroyed. ra_hwdec_mapper_map unmaps before
    // mapping; deleting here makes a timed-out acquire sample a destroyed texture.
    (void)mapper;
}''',
      'FONGMI_IMAGE_UNMAP_HOLDS')
patch(image_reader,
      '''    if (!load_lib_functions(p, hw->log))
        return -1;
''',
      '''    if (!load_lib_functions(p, hw->log))
        return -1;
    // Optional (API 26): returns a buffer with a GPU release fence instead of a CPU wait.
    *(void **)&p->AImage_deleteAsync = dlsym(p->lib_handle, "AImage_deleteAsync");
''',
      'AImage_deleteAsync = dlsym')
patch(image_reader,
      '    void (*AImage_delete)(AImage *);',
      '''    void (*AImage_delete)(AImage *);
    void (*AImage_deleteAsync)(AImage *, int);''',
      '(*AImage_deleteAsync)')
patch(image_reader,
      '''        av_mediacodec_release_buffer(buffer, 1);
    }

    bool image_available = false;
    mp_mutex_lock(&p->lock);
    if (!p->image_available) {
        mp_cond_timedwait(&p->cond, &p->lock, MP_TIME_MS_TO_NS(100));
        if (!p->image_available)
            MP_WARN(mapper, "Waiting for frame timed out!\\n");
    }
    image_available = p->image_available;
    p->image_available = false;
    mp_mutex_unlock(&p->lock);

    media_status_t ret = o->AImageReader_acquireLatestImage(o->reader, &p->image);
    if (ret != AMEDIA_OK) {
        MP_ERR(mapper, "acquireLatestImage failed: %d\\n", ret);
        // If we merely timed out waiting return success anyway to avoid
        // flashing frames of render errors.
        return image_available ? -1 : 0;
    }
    mp_assert(p->image);''',
      '''        av_mediacodec_release_buffer(buffer, 1);
    }

    AImage *displayed = p->image;
    EGLImageKHR displayed_egl = p->egl_image;
    p->image = NULL;
    p->egl_image = NULL;

    bool image_available = false;
    mp_mutex_lock(&p->lock);
    if (!p->image_available) {
        mp_cond_timedwait(&p->cond, &p->lock, MP_TIME_MS_TO_NS(100));
        if (!p->image_available)
            MP_WARN(mapper, "Waiting for frame timed out!\\n");
    }
    image_available = p->image_available;
    p->image_available = false;
    mp_mutex_unlock(&p->lock);

    AImage *acquired = NULL;
    media_status_t ret = o->AImageReader_acquireLatestImage(o->reader, &acquired);
    if (ret != AMEDIA_OK) {
        mp_mutex_lock(&p->lock);
        if (!p->image_available)
            mp_cond_timedwait(&p->cond, &p->lock, MP_TIME_MS_TO_NS(40));
        image_available = p->image_available;
        p->image_available = false;
        mp_mutex_unlock(&p->lock);
        ret = o->AImageReader_acquireLatestImage(o->reader, &acquired);
    }
    if (ret != AMEDIA_OK || !acquired) {
        MP_ERR(mapper, "acquireLatestImage failed: %d\\n", ret);
        p->image = displayed;
        p->egl_image = displayed_egl;
        // Keep the last real frame: still-mapped codec buffer, or the owned copy.
        // Success with neither samples a dead texture (green).
        (void)image_available;
        return (displayed_egl || p->copy_valid) ? 0 : -1;
    }
    if (displayed || displayed_egl) {
        p->image = displayed;
        p->egl_image = displayed_egl;
        release_displayed_image(mapper);
    }
    p->image = acquired;
    mp_assert(p->image);''',
      'return (displayed_egl || p->copy_valid) ? 0 : -1;')
patch(image_reader,
      '    media_status_t (*AImage_getHardwareBuffer)(const AImage *, AHardwareBuffer **);',
      '''    media_status_t (*AImage_getHardwareBuffer)(const AImage *, AHardwareBuffer **);
    media_status_t (*AImage_getCropRect)(const AImage *, AImageCropRect *);''',
      '(*AImage_getCropRect)')
patch(image_reader,
      '    { "AImage_getHardwareBuffer", offsetof(struct priv_owner, AImage_getHardwareBuffer) },',
      '''    { "AImage_getHardwareBuffer", offsetof(struct priv_owner, AImage_getHardwareBuffer) },
    { "AImage_getCropRect", offsetof(struct priv_owner, AImage_getCropRect) },''',
      'offsetof(struct priv_owner, AImage_getCropRect)')
patch(image_reader, '    bool image_available;',
      '    bool image_available;\n    bool buffer_format_logged;', 'buffer_format_logged;')
patch(image_reader,
      '    GLuint gl_texture;\n    AImage *image;',
      '''    GLuint gl_texture;
    // FONGMI_IMAGE_COPY: owned RGBA target. The codec buffer is released after
    // this copy, so a 3-4 slot pool is not pinned for the whole displayed frame.
    GLuint copy_tex;
    GLuint copy_fbo;
    GLuint copy_prog;
    GLuint copy_vbo;
    int copy_w, copy_h;
    bool copy_10bit;
    // The owned texture holds a complete decoded frame (usable after a failed acquire).
    bool copy_valid;
    bool native_fence;
    void *(EGLAPIENTRY *CreateSyncKHR)(EGLDisplay, EGLenum, const EGLint *);
    EGLBoolean (EGLAPIENTRY *DestroySyncKHR)(EGLDisplay, void *);
    EGLint (EGLAPIENTRY *DupNativeFenceFDANDROID)(EGLDisplay, void *);
    AImage *image;''',
      'FONGMI_IMAGE_COPY')
patch(image_reader,
      'static int mapper_init(struct ra_hwdec_mapper *mapper)\n{',
      r'''static GLuint compile_copy_shader(GL *gl, GLenum type, const char *src)
{
    GLuint shader = gl->CreateShader(type);
    gl->ShaderSource(shader, 1, &src, NULL);
    gl->CompileShader(shader);
    GLint ok = 0;
    gl->GetShaderiv(shader, GL_COMPILE_STATUS, &ok);
    if (!ok) {
        gl->DeleteShader(shader);
        return 0;
    }
    return shader;
}

static bool init_frame_copy(struct ra_hwdec_mapper *mapper)
{
    struct priv *p = mapper->priv;
    GL *gl = ra_gl_get(mapper->ra);
    static const char *vs =
        "#version 100\n"
        "attribute vec2 pos;\n"
        "attribute vec2 uv;\n"
        "varying vec2 v_uv;\n"
        "void main(){ v_uv = uv; gl_Position = vec4(pos, 0.0, 1.0); }\n";
    static const char *fs =
        "#version 100\n"
        "#extension GL_OES_EGL_image_external : require\n"
        "#ifdef GL_FRAGMENT_PRECISION_HIGH\n"
        "precision highp float;\n"
        "#else\n"
        "precision mediump float;\n"
        "#endif\n"
        "uniform samplerExternalOES tex;\n"
        "varying vec2 v_uv;\n"
        "void main(){ gl_FragColor = texture2D(tex, v_uv); }\n";
    GLuint vert = compile_copy_shader(gl, GL_VERTEX_SHADER, vs);
    GLuint frag = compile_copy_shader(gl, GL_FRAGMENT_SHADER, fs);
    if (!vert || !frag) {
        if (vert)
            gl->DeleteShader(vert);
        if (frag)
            gl->DeleteShader(frag);
        return false;
    }
    GLuint prog = gl->CreateProgram();
    gl->BindAttribLocation(prog, 0, "pos");
    gl->BindAttribLocation(prog, 1, "uv");
    gl->AttachShader(prog, vert);
    gl->AttachShader(prog, frag);
    gl->LinkProgram(prog);
    gl->DeleteShader(vert);
    gl->DeleteShader(frag);
    GLint linked = 0;
    gl->GetProgramiv((GLenum)prog, GL_LINK_STATUS, &linked);
    if (!linked) {
        gl->DeleteProgram(prog);
        return false;
    }
    gl->GenTextures(1, &p->copy_tex);
    gl->BindTexture(GL_TEXTURE_2D, p->copy_tex);
    gl->TexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    gl->TexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    gl->TexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    gl->TexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    gl->BindTexture(GL_TEXTURE_2D, 0);
    gl->GenFramebuffers(1, &p->copy_fbo);
    gl->GenBuffers(1, &p->copy_vbo);
    p->copy_prog = prog;
    return true;
}

#ifndef GL_VIEWPORT
#define GL_VIEWPORT 0x0BA2
#endif
#ifndef GL_TEXTURE_BINDING_2D
#define GL_TEXTURE_BINDING_2D 0x8069
#endif
#ifndef GL_TEXTURE_BINDING_EXTERNAL_OES
#define GL_TEXTURE_BINDING_EXTERNAL_OES 0x8D67
#endif
#ifndef GL_ARRAY_BUFFER_BINDING
#define GL_ARRAY_BUFFER_BINDING 0x8894
#endif
#ifndef GL_ACTIVE_TEXTURE
#define GL_ACTIVE_TEXTURE 0x84E0
#endif
#ifndef GL_VERTEX_ARRAY_BINDING
#define GL_VERTEX_ARRAY_BINDING 0x85B5
#endif
#ifndef GL_RGB10_A2
#define GL_RGB10_A2 0x8059
#endif
#ifndef GL_UNSIGNED_INT_2_10_10_10_REV
#define GL_UNSIGNED_INT_2_10_10_10_REV 0x8368
#endif

static bool drain_gl_errors(GL *gl)
{
    bool failed = false;
    for (int n = 0; n < 8 && gl->GetError() != GL_NO_ERROR; n++)
        failed = true;
    return failed;
}

static bool alloc_copy_target(struct ra_hwdec_mapper *mapper, int w, int h, bool tenbit)
{
    struct priv *p = mapper->priv;
    GL *gl = ra_gl_get(mapper->ra);
    gl->BindTexture(GL_TEXTURE_2D, p->copy_tex);
    if (tenbit) {
        gl->TexImage2D(GL_TEXTURE_2D, 0, GL_RGB10_A2, w, h, 0, GL_RGBA,
                       GL_UNSIGNED_INT_2_10_10_10_REV, NULL);
    } else {
        gl->TexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, w, h, 0, GL_RGBA,
                       GL_UNSIGNED_BYTE, NULL);
    }
    gl->BindFramebuffer(GL_FRAMEBUFFER, p->copy_fbo);
    gl->FramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0,
                             GL_TEXTURE_2D, p->copy_tex, 0);
    bool ok = gl->CheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE;
    ok &= !drain_gl_errors(gl);
    p->copy_10bit = ok && tenbit;
    return ok;
}

static bool copy_external_frame(struct ra_hwdec_mapper *mapper, int w, int h)
{
    struct priv *p = mapper->priv;
    GL *gl = ra_gl_get(mapper->ra);
    // mpv's renderer owns the context state: save and restore everything this pass binds.
    GLint fbo = 0, prog = 0, active = 0, tex2d = 0, texext = 0, abuf = 0, vao = 0;
    GLint vp[4] = {0};
    gl->GetIntegerv(GL_FRAMEBUFFER_BINDING, &fbo);
    gl->GetIntegerv(GL_CURRENT_PROGRAM, &prog);
    gl->GetIntegerv(GL_VIEWPORT, vp);
    gl->GetIntegerv(GL_ACTIVE_TEXTURE, &active);
    gl->ActiveTexture(GL_TEXTURE0);
    gl->GetIntegerv(GL_TEXTURE_BINDING_2D, &tex2d);
    gl->GetIntegerv(GL_TEXTURE_BINDING_EXTERNAL_OES, &texext);
    gl->GetIntegerv(GL_ARRAY_BUFFER_BINDING, &abuf);
    if (gl->BindVertexArray) {
        gl->GetIntegerv(GL_VERTEX_ARRAY_BINDING, &vao);
        gl->BindVertexArray(0);
    }

    bool ok = true;
    if (p->copy_w != w || p->copy_h != h) {
        p->copy_valid = false;
        // 10-bit intermediate keeps Main10 precision through the copy (ES3 renderable).
        ok = (gl->es >= 300 && alloc_copy_target(mapper, w, h, true))
            || alloc_copy_target(mapper, w, h, false);
        if (ok) {
            p->copy_w = w;
            p->copy_h = h;
            MP_INFO(mapper, "FONGMI_IMAGE_COPY target %dx%d %s\n", w, h,
                    p->copy_10bit ? "RGB10_A2" : "RGBA8");
        }
    }
    if (ok) {
        gl->BindFramebuffer(GL_FRAMEBUFFER, p->copy_fbo);
        // ra_gl leaves both disabled after each pass; keep the full-target copy unclipped.
        gl->Disable(GL_SCISSOR_TEST);
        gl->Disable(GL_BLEND);
        gl->Viewport(0, 0, w, h);
        gl->UseProgram(p->copy_prog);
        gl->BindTexture(GL_TEXTURE_EXTERNAL_OES, p->gl_texture);
        gl->Uniform1i(gl->GetUniformLocation(p->copy_prog, "tex"), 0);
        static const float verts[] = {
            -1.f, -1.f, 0.f, 0.f,
             1.f, -1.f, 1.f, 0.f,
            -1.f,  1.f, 0.f, 1.f,
             1.f,  1.f, 1.f, 1.f,
        };
        gl->BindBuffer(GL_ARRAY_BUFFER, p->copy_vbo);
        gl->BufferData(GL_ARRAY_BUFFER, (intptr_t)sizeof(verts), verts, GL_STREAM_DRAW);
        gl->EnableVertexAttribArray(0);
        gl->EnableVertexAttribArray(1);
        gl->VertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, (GLsizei)(4 * sizeof(float)), (void *)0);
        gl->VertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, (GLsizei)(4 * sizeof(float)),
                                (void *)(uintptr_t)(2 * sizeof(float)));
        gl->DrawArrays(GL_TRIANGLE_STRIP, 0, 4);
        gl->DisableVertexAttribArray(0);
        gl->DisableVertexAttribArray(1);
        ok = !drain_gl_errors(gl);
    }

    if (gl->BindVertexArray)
        gl->BindVertexArray(vao);
    gl->BindBuffer(GL_ARRAY_BUFFER, abuf);
    gl->BindTexture(GL_TEXTURE_EXTERNAL_OES, texext);
    gl->BindTexture(GL_TEXTURE_2D, tex2d);
    gl->ActiveTexture(active);
    gl->UseProgram(prog);
    gl->BindFramebuffer(GL_FRAMEBUFFER, fbo);
    gl->Viewport(vp[0], vp[1], vp[2], vp[3]);
    p->copy_valid = ok;
    return ok;
}

static void free_frame_copy(struct ra_hwdec_mapper *mapper)
{
    struct priv *p = mapper->priv;
    GL *gl = ra_gl_get(mapper->ra);
    if (p->copy_prog)
        gl->DeleteProgram(p->copy_prog);
    if (p->copy_fbo)
        gl->DeleteFramebuffers(1, &p->copy_fbo);
    if (p->copy_vbo)
        gl->DeleteBuffers(1, &p->copy_vbo);
    if (p->copy_tex)
        gl->DeleteTextures(1, &p->copy_tex);
    p->copy_prog = p->copy_fbo = p->copy_vbo = p->copy_tex = 0;
    p->copy_w = p->copy_h = 0;
    p->copy_valid = false;
}

// A failed copy must not present the owned texture: rewrap the codec buffer itself
// (upstream zero-copy path) for the rest of this mapper's life.
static bool disable_frame_copy(struct ra_hwdec_mapper *mapper, int w, int h)
{
    struct priv *p = mapper->priv;
    MP_WARN(mapper, "FONGMI_IMAGE_COPY failed; sampling the codec buffer directly\n");
    free_frame_copy(mapper);
    ra_tex_free(mapper->ra, &mapper->tex[0]);
    struct ra_tex_params params = {
        .dimensions = 2,
        .w = w,
        .h = h,
        .d = 1,
        .format = ra_find_unorm_format(mapper->ra, 1, 4),
        .render_src = true,
        .src_linear = true,
        .external_oes = true,
    };
    struct ra_tex *direct = ra_create_wrapped_tex(mapper->ra, &params, p->gl_texture);
    mapper->tex[0] = direct;
    return direct != NULL;
}

static int mapper_init(struct ra_hwdec_mapper *mapper)
{''',
      'static bool copy_external_frame')
patch(image_reader,
      '''    struct ra_tex_params params = {
        .dimensions = 2,
        .w = mapper->src_params.w,
        .h = mapper->src_params.h,
        .d = 1,
        .format = ra_find_unorm_format(mapper->ra, 1, 4),
        .render_src = true,
        .src_linear = true,
        .external_oes = true,
    };''',
      '''    const char *egl_exts = eglQueryString(eglGetCurrentDisplay(), EGL_EXTENSIONS);
    if (gl_check_extension(egl_exts, "EGL_ANDROID_native_fence_sync")) {
        p->CreateSyncKHR = (void *)eglGetProcAddress("eglCreateSyncKHR");
        p->DestroySyncKHR = (void *)eglGetProcAddress("eglDestroySyncKHR");
        p->DupNativeFenceFDANDROID = (void *)eglGetProcAddress("eglDupNativeFenceFDANDROID");
        p->native_fence = p->CreateSyncKHR && p->DestroySyncKHR && p->DupNativeFenceFDANDROID;
    }
    if (!init_frame_copy(mapper)) {
        free_frame_copy(mapper);
        MP_WARN(mapper, "FONGMI_IMAGE_COPY unavailable; holding the codec buffer\\n");
    }
    MP_INFO(mapper, "FONGMI_IMAGE_RELEASE copy=%d native-fence=%d async-delete=%d\\n",
            p->copy_prog != 0, p->native_fence, o->AImage_deleteAsync != NULL);
    struct ra_tex_params params = {
        .dimensions = 2,
        .w = mapper->src_params.w,
        .h = mapper->src_params.h,
        .d = 1,
        .format = ra_find_unorm_format(mapper->ra, 1, 4),
        .render_src = true,
        .src_linear = true,
        .external_oes = p->copy_prog == 0,
    };''',
      'init_frame_copy(mapper)')
patch(image_reader,
      '    mapper->tex[0] = ra_create_wrapped_tex(mapper->ra, &params, p->gl_texture);',
      '''    mapper->tex[0] = ra_create_wrapped_tex(mapper->ra, &params,
                                          p->copy_prog ? p->copy_tex : p->gl_texture);''',
      'p->copy_prog ? p->copy_tex')
patch(image_reader,
      '''    gl->BindTexture(GL_TEXTURE_EXTERNAL_OES, p->gl_texture);
    p->EGLImageTargetTexture2DOES(GL_TEXTURE_EXTERNAL_OES, p->egl_image);
    gl->BindTexture(GL_TEXTURE_EXTERNAL_OES, 0);

    return 0;
}''',
      '''    gl->BindTexture(GL_TEXTURE_EXTERNAL_OES, p->gl_texture);
    p->EGLImageTargetTexture2DOES(GL_TEXTURE_EXTERNAL_OES, p->egl_image);
    gl->BindTexture(GL_TEXTURE_EXTERNAL_OES, 0);

    if (p->copy_prog) {
        if (copy_external_frame(mapper, (int)d.width, (int)d.height))
            release_displayed_image(mapper);
        else if (!disable_frame_copy(mapper, (int)d.width, (int)d.height))
            return -1;
    }

    return 0;
}''',
      'copy_external_frame(mapper')
patch(image_reader,
      '''    gl->DeleteTextures(1, &p->gl_texture);
    p->gl_texture = 0;''',
      '''    free_frame_copy(mapper);
    gl->DeleteTextures(1, &p->gl_texture);
    p->gl_texture = 0;''',
      'free_frame_copy(mapper);\n    gl->DeleteTextures')
# Preserve the decoder's declared visible rectangle on hardware frames. Unlike the
# software copy path, the hardware wrapper used to retain only cropped dimensions
# and lose nonzero origins. ImageReader can report a different/default crop, so the
# downstream GPU needs the per-frame MediaFormat origin, not a guessed border inset.
patch(ffmpeg / 'libavcodec/mediacodecdec_common.c',
      '''    AVMediaCodecBuffer *buffer = NULL;

    frame->buf[0] = NULL;
    frame->width = avctx->width;
    frame->height = avctx->height;
    frame->format = avctx->pix_fmt;''',
      '''    AVMediaCodecBuffer *buffer = NULL;

    frame->buf[0] = NULL;
    frame->width = avctx->width;
    frame->height = avctx->height;
    // FONGMI_MEDIACODEC_FRAME_CROP: inclusive MediaFormat -> AVFrame crop origin.
    // Keep ALL visible pixels, including the last source row/column. Represent
    // prefix padding in frame dimensions; the actual allocation may be larger.
    if (s->crop_left >= 0 && s->crop_top >= 0 &&
        (int64_t)s->crop_right + 1 - s->crop_left == frame->width &&
        (int64_t)s->crop_bottom + 1 - s->crop_top == frame->height &&
        frame->width > 0 && frame->height > 0 &&
        frame->width <= INT_MAX - s->crop_left &&
        frame->height <= INT_MAX - s->crop_top) {
        frame->width += s->crop_left;
        frame->height += s->crop_top;
        frame->crop_left = s->crop_left;
        frame->crop_top = s->crop_top;
    }
    frame->format = avctx->pix_fmt;''', 'FONGMI_MEDIACODEC_FRAME_CROP')
# Texture coordinate normalization must use actual imported/copied storage size,
# not the decoder's visible size. mpv already applies AVFrame crop via params.crop.
patch(image_reader,
      '''    gl->BindTexture(GL_TEXTURE_EXTERNAL_OES, p->gl_texture);
    p->EGLImageTargetTexture2DOES(GL_TEXTURE_EXTERNAL_OES, p->egl_image);
    gl->BindTexture(GL_TEXTURE_EXTERNAL_OES, 0);

    if (p->copy_prog) {''',
      '''    gl->BindTexture(GL_TEXTURE_EXTERNAL_OES, p->gl_texture);
    p->EGLImageTargetTexture2DOES(GL_TEXTURE_EXTERNAL_OES, p->egl_image);
    gl->BindTexture(GL_TEXTURE_EXTERNAL_OES, 0);

    // FONGMI_IMAGE_STORAGE_SIZE: expose real storage dimensions to GPU sampling.
    mapper->tex[0]->params.w = (int)d.width;
    mapper->tex[0]->params.h = (int)d.height;
    if (p->copy_prog) {''', 'FONGMI_IMAGE_STORAGE_SIZE')
print('PASS pinned async JNI, cancellation, VO-frame signal and bounded read-only buffer/SPS/fence diagnostics; no bitmap bridge')
