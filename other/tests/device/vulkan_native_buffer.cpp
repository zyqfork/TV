// Exercise the Android system-loader ANB path, not a hot-loaded ICD's private AHB swapchain.
#include <android/hardware_buffer.h>
#include <gui/BufferItem.h>
#include <gui/BufferItemConsumer.h>
#include <gui/BufferQueue.h>
#include <gui/Surface.h>
#include <ui/GraphicBuffer.h>
#include <vndk/hardware_buffer.h>
#include <vulkan/vulkan.h>
#include <vulkan/vulkan_android.h>
#include <vulkan/vk_android_native_buffer.h>

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <vector>

static void check(VkResult result, const char *operation) {
    std::printf("%s: %d\n", operation, result);
    std::fflush(stdout);
    if (result != VK_SUCCESS) std::exit(1);
}

static void test_buffer(VkDevice device, VkQueue queue, VkCommandPool pool, bool cpu_read) {
    AHardwareBuffer_Desc desc = {};
    desc.width = 64;
    desc.height = 64;
    desc.layers = 1;
    desc.format = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
    desc.usage = AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT | AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE;
    if (cpu_read) desc.usage |= AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN;
    AHardwareBuffer *buffer = nullptr;
    int allocation = AHardwareBuffer_allocate(&desc, &buffer);
    std::printf("AHardwareBuffer_allocate(cpu_read=%d): %d\n", cpu_read, allocation);
    if (allocation || !buffer) std::exit(1);
    AHardwareBuffer_describe(buffer, &desc);
    auto handle = AHardwareBuffer_getNativeHandle(buffer);
    if (!handle || handle->numFds < 1 || handle->numFds > 16) std::exit(1);
    std::printf("ANB %ux%u format=%u stride=%u usage=%llu fds=%d ints=%d\n",
                desc.width, desc.height, desc.format, desc.stride,
                static_cast<unsigned long long>(desc.usage), handle->numFds, handle->numInts);

    VkNativeBufferANDROID native = {};
    native.sType = VK_STRUCTURE_TYPE_NATIVE_BUFFER_ANDROID;
    native.handle = handle;
    native.stride = desc.stride;
    native.format = desc.format;
    native.usage = desc.usage;
    VkImageCreateInfo image_info = {};
    image_info.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
    image_info.pNext = &native;
    image_info.imageType = VK_IMAGE_TYPE_2D;
    image_info.format = VK_FORMAT_R8G8B8A8_UNORM;
    image_info.extent = {desc.width, desc.height, 1};
    image_info.mipLevels = image_info.arrayLayers = 1;
    image_info.samples = VK_SAMPLE_COUNT_1_BIT;
    image_info.tiling = VK_IMAGE_TILING_OPTIMAL;
    image_info.usage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
    VkImage image = VK_NULL_HANDLE;
    check(vkCreateImage(device, &image_info, nullptr, &image), "vkCreateImage(ANB)");

    VkCommandBufferAllocateInfo allocate = {};
    allocate.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    allocate.commandPool = pool;
    allocate.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    allocate.commandBufferCount = 1;
    VkCommandBuffer commands;
    check(vkAllocateCommandBuffers(device, &allocate, &commands), "vkAllocateCommandBuffers");
    VkCommandBufferBeginInfo begin = {};
    begin.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    check(vkBeginCommandBuffer(commands, &begin), "vkBeginCommandBuffer");
    VkImageMemoryBarrier barrier = {};
    barrier.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    barrier.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    barrier.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    barrier.newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
    barrier.srcQueueFamilyIndex = barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    barrier.image = image;
    barrier.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    vkCmdPipelineBarrier(commands, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                         0, 0, nullptr, 0, nullptr, 1, &barrier);
    VkClearColorValue red = {{1.0f, 0.0f, 0.0f, 1.0f}};
    vkCmdClearColorImage(commands, image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, &red,
                         1, &barrier.subresourceRange);
    barrier.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    barrier.dstAccessMask = VK_ACCESS_HOST_READ_BIT;
    barrier.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
    barrier.newLayout = VK_IMAGE_LAYOUT_GENERAL;
    vkCmdPipelineBarrier(commands, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_HOST_BIT,
                         0, 0, nullptr, 0, nullptr, 1, &barrier);
    check(vkEndCommandBuffer(commands), "vkEndCommandBuffer");
    VkFenceCreateInfo fence_info = {};
    fence_info.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
    VkFence fence;
    check(vkCreateFence(device, &fence_info, nullptr, &fence), "vkCreateFence");
    VkSubmitInfo submit = {};
    submit.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    submit.commandBufferCount = 1;
    submit.pCommandBuffers = &commands;
    check(vkQueueSubmit(queue, 1, &submit, fence), "vkQueueSubmit");
    check(vkWaitForFences(device, 1, &fence, VK_TRUE, 2000000000ULL), "vkWaitForFences(2s)");
    if (cpu_read) {
        void *pixels = nullptr;
        if (AHardwareBuffer_lock(buffer, AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN, -1, nullptr, &pixels)
                || !pixels) std::exit(1);
        auto rgba = static_cast<const unsigned char *>(pixels);
        bool red_pixel = rgba[0] == 255 && rgba[1] == 0 && rgba[2] == 0 && rgba[3] == 255;
        std::printf("GPU readback RGBA=%u,%u,%u,%u\n", rgba[0], rgba[1], rgba[2], rgba[3]);
        if (AHardwareBuffer_unlock(buffer, nullptr) || !red_pixel) std::exit(1);
    }
    vkDestroyFence(device, fence, nullptr);
    vkFreeCommandBuffers(device, pool, 1, &commands);
    vkDestroyImage(device, image, nullptr);
    AHardwareBuffer_release(buffer);
}

// A private BufferQueue exercises system WSI without SurfaceFlinger or the physical display.
static void test_wsi(VkInstance instance, VkPhysicalDevice physical, VkDevice device,
                     VkQueue queue, VkCommandPool pool) {
    using namespace android;
    sp<IGraphicBufferProducer> producer;
    sp<IGraphicBufferConsumer> endpoint;
    BufferQueue::createBufferQueue(&producer, &endpoint);
    sp<BufferItemConsumer> consumer = new BufferItemConsumer(endpoint, GraphicBuffer::USAGE_SW_READ_OFTEN, 1);
    if (endpoint->setDefaultBufferSize(64, 64) != OK) std::exit(1);
    sp<Surface> window = new Surface(producer, true);
    VkAndroidSurfaceCreateInfoKHR surface_info = {};
    surface_info.sType = VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR;
    surface_info.window = window.get();
    VkSurfaceKHR surface;
    check(vkCreateAndroidSurfaceKHR(instance, &surface_info, nullptr, &surface), "vkCreateAndroidSurfaceKHR");
    VkSurfaceCapabilitiesKHR caps;
    check(vkGetPhysicalDeviceSurfaceCapabilitiesKHR(physical, surface, &caps), "surface capabilities");
    uint32_t count = 0;
    check(vkGetPhysicalDeviceSurfaceFormatsKHR(physical, surface, &count, nullptr), "surface formats(count)");
    std::vector<VkSurfaceFormatKHR> formats(count);
    check(vkGetPhysicalDeviceSurfaceFormatsKHR(physical, surface, &count, formats.data()), "surface formats");
    VkSurfaceFormatKHR format = {};
    for (auto candidate : formats) {
        if (candidate.format == VK_FORMAT_R8G8B8A8_UNORM || candidate.format == VK_FORMAT_B8G8R8A8_UNORM) {
            format = candidate;
            break;
        }
    }
    if (format.format == VK_FORMAT_UNDEFINED) std::exit(1);
    VkSwapchainCreateInfoKHR swap_info = {};
    swap_info.sType = VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR;
    swap_info.surface = surface;
    swap_info.minImageCount = caps.minImageCount;
    swap_info.imageFormat = format.format;
    swap_info.imageColorSpace = format.colorSpace;
    swap_info.imageExtent = {64, 64};
    swap_info.imageArrayLayers = 1;
    swap_info.imageUsage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
    swap_info.preTransform = caps.currentTransform;
    swap_info.compositeAlpha = VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR;
    swap_info.presentMode = VK_PRESENT_MODE_FIFO_KHR;
    swap_info.clipped = VK_TRUE;
    VkSwapchainKHR swapchain;
    check(vkCreateSwapchainKHR(device, &swap_info, nullptr, &swapchain), "vkCreateSwapchainKHR");
    check(vkGetSwapchainImagesKHR(device, swapchain, &count, nullptr), "swapchain images(count)");
    std::vector<VkImage> images(count);
    check(vkGetSwapchainImagesKHR(device, swapchain, &count, images.data()), "swapchain images");
    for (int frame = 0; frame < 300; ++frame) {
        VkSemaphoreCreateInfo semaphore_info = {};
        semaphore_info.sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO;
        VkSemaphore ready, rendered;
        check(vkCreateSemaphore(device, &semaphore_info, nullptr, &ready), "image semaphore");
        check(vkCreateSemaphore(device, &semaphore_info, nullptr, &rendered), "render semaphore");
        uint32_t index;
        check(vkAcquireNextImageKHR(device, swapchain, 2000000000ULL, ready, VK_NULL_HANDLE, &index), "acquire");
        VkCommandBufferAllocateInfo allocation = {};
        allocation.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
        allocation.commandPool = pool;
        allocation.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
        allocation.commandBufferCount = 1;
        VkCommandBuffer commands;
        check(vkAllocateCommandBuffers(device, &allocation, &commands), "WSI command buffer");
        VkCommandBufferBeginInfo begin = {};
        begin.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
        check(vkBeginCommandBuffer(commands, &begin), "WSI begin");
        VkImageMemoryBarrier barrier = {};
        barrier.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
        barrier.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        barrier.newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
        barrier.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
        barrier.srcQueueFamilyIndex = barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        barrier.image = images[index];
        barrier.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
        vkCmdPipelineBarrier(commands, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                             0, 0, nullptr, 0, nullptr, 1, &barrier);
        VkClearColorValue green = {{0, 1, 0, 1}};
        vkCmdClearColorImage(commands, images[index], VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, &green,
                            1, &barrier.subresourceRange);
        barrier.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
        barrier.dstAccessMask = 0;
        barrier.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
        barrier.newLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
        vkCmdPipelineBarrier(commands, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,
                             0, 0, nullptr, 0, nullptr, 1, &barrier);
        check(vkEndCommandBuffer(commands), "WSI end");
        VkPipelineStageFlags stage = VK_PIPELINE_STAGE_TRANSFER_BIT;
        VkSubmitInfo submit = {};
        submit.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
        submit.waitSemaphoreCount = submit.signalSemaphoreCount = submit.commandBufferCount = 1;
        submit.pWaitSemaphores = &ready;
        submit.pWaitDstStageMask = &stage;
        submit.pSignalSemaphores = &rendered;
        submit.pCommandBuffers = &commands;
        check(vkQueueSubmit(queue, 1, &submit, VK_NULL_HANDLE), "WSI submit");
        VkPresentInfoKHR present = {};
        present.sType = VK_STRUCTURE_TYPE_PRESENT_INFO_KHR;
        present.waitSemaphoreCount = present.swapchainCount = 1;
        present.pWaitSemaphores = &rendered;
        present.pSwapchains = &swapchain;
        present.pImageIndices = &index;
        check(vkQueuePresentKHR(queue, &present), "vkQueuePresentKHR");
        BufferItem item;
        if (consumer->acquireBuffer(&item, 0, false) != OK || item.mGraphicBuffer == nullptr) std::exit(1);
        if (item.mFence != nullptr && item.mFence->wait(2000) != OK) std::exit(1);
        void *pixels = nullptr;
        if (item.mGraphicBuffer->lock(GraphicBuffer::USAGE_SW_READ_OFTEN, &pixels) != OK || !pixels) std::exit(1);
        auto rgba = static_cast<const unsigned char *>(pixels);
        bool green_pixel = rgba[0] == 0 && rgba[1] == 255 && rgba[2] == 0 && rgba[3] == 255;
        if (item.mGraphicBuffer->unlock() != OK || !green_pixel || consumer->releaseBuffer(item) != OK) std::exit(1);
        check(vkQueueWaitIdle(queue), "WSI idle");
        vkFreeCommandBuffers(device, pool, 1, &commands);
        vkDestroySemaphore(device, rendered, nullptr);
        vkDestroySemaphore(device, ready, nullptr);
    }
    vkDestroySwapchainKHR(device, swapchain, nullptr);
    vkDestroySurfaceKHR(instance, surface, nullptr);
    std::puts("PASS 300 Android swapchain frames with GPU pixel verification (no physical display)");
}

int main(int argc, char **argv) {
    VkApplicationInfo app = {};
    app.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    app.apiVersion = VK_API_VERSION_1_1;
    VkInstanceCreateInfo instance_info = {};
    instance_info.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    instance_info.pApplicationInfo = &app;
    const char *instance_extensions[] = {VK_KHR_SURFACE_EXTENSION_NAME, VK_KHR_ANDROID_SURFACE_EXTENSION_NAME};
    instance_info.enabledExtensionCount = 2;
    instance_info.ppEnabledExtensionNames = instance_extensions;
    VkInstance instance;
    check(vkCreateInstance(&instance_info, nullptr, &instance), "vkCreateInstance");
    uint32_t count = 0;
    check(vkEnumeratePhysicalDevices(instance, &count, nullptr), "vkEnumeratePhysicalDevices(count)");
    if (!count) return 1;
    std::vector<VkPhysicalDevice> devices(count);
    check(vkEnumeratePhysicalDevices(instance, &count, devices.data()), "vkEnumeratePhysicalDevices");
    VkPhysicalDeviceProperties properties;
    vkGetPhysicalDeviceProperties(devices[0], &properties);
    std::printf("device=%s\n", properties.deviceName);
    vkGetPhysicalDeviceQueueFamilyProperties(devices[0], &count, nullptr);
    std::vector<VkQueueFamilyProperties> queues(count);
    vkGetPhysicalDeviceQueueFamilyProperties(devices[0], &count, queues.data());
    uint32_t family = 0;
    while (family < count && !(queues[family].queueFlags & VK_QUEUE_GRAPHICS_BIT)) ++family;
    if (family == count) return 1;
    float priority = 1.0f;
    VkDeviceQueueCreateInfo queue_info = {};
    queue_info.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
    queue_info.queueFamilyIndex = family;
    queue_info.queueCount = 1;
    queue_info.pQueuePriorities = &priority;
    const char *extensions[] = {VK_KHR_SWAPCHAIN_EXTENSION_NAME};
    VkDeviceCreateInfo device_info = {};
    device_info.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
    device_info.queueCreateInfoCount = 1;
    device_info.pQueueCreateInfos = &queue_info;
    device_info.enabledExtensionCount = 1;
    device_info.ppEnabledExtensionNames = extensions;
    VkDevice device;
    check(vkCreateDevice(devices[0], &device_info, nullptr, &device), "vkCreateDevice");
    VkQueue queue;
    vkGetDeviceQueue(device, family, 0, &queue);
    VkCommandPoolCreateInfo pool_info = {};
    pool_info.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
    pool_info.queueFamilyIndex = family;
    VkCommandPool pool;
    check(vkCreateCommandPool(device, &pool_info, nullptr, &pool), "vkCreateCommandPool");
    if (argc == 2 && std::strcmp(argv[1], "--wsi") == 0) {
        test_wsi(instance, devices[0], device, queue, pool);
    } else {
        test_buffer(device, queue, pool, false);
        test_buffer(device, queue, pool, true);
        std::puts("PASS Android native-buffer import, GPU submit and pixel readback");
    }
    vkDestroyCommandPool(device, pool, nullptr);
    vkDestroyDevice(device, nullptr);
    vkDestroyInstance(instance, nullptr);
    return 0;
}
