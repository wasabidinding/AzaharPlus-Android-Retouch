// Copyright Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the license.txt file included.

#include <atomic>
#include <limits>
#include <condition_variable>
#include <mutex>
#include <queue>
#include "common/polyfill_thread.h"
#include "video_core/renderer_vulkan/vk_swapchain.h"

VK_DEFINE_HANDLE(VmaAllocation)

namespace Frontend {
class EmuWindow;
}

namespace Vulkan {

class Instance;
class Swapchain;
class Scheduler;
class RenderManager;

struct Frame {
    u32 width;
    u32 height;
    VmaAllocation allocation;
    vk::Framebuffer framebuffer;
    vk::Image image;
    vk::ImageView image_view;
    vk::Semaphore render_ready;
    vk::Fence present_done;
    vk::CommandBuffer cmdbuf;
};

class PresentWindow final {
public:
    explicit PresentWindow(Frontend::EmuWindow& emu_window, const Instance& instance,
                           Scheduler& scheduler, bool low_refresh_rate);
    ~PresentWindow();

    /// Waits for all queued frames to finish presenting.
    void WaitPresent();

    /// Returns the last used render frame.
    Frame* GetRenderFrame();

    /// Recreates the render frame to match provided parameters.
    void RecreateFrame(Frame* frame, u32 width, u32 height);

    /// Queues the provided frame for presentation.
    void Present(Frame* frame);

    /// Presents the most recently presented frame again, without rendering a new one.
    /// Used while emulation is paused and the platform surface has been recreated
    /// (e.g. Android screen lock / app switch), so the window is not left blank.
    void PresentLastFrame();

    /// This is called to notify the rendering backend of a surface change
    void NotifySurfaceChanged();

    [[nodiscard]] vk::RenderPass Renderpass() const noexcept {
        return present_renderpass;
    }

    u32 ImageCount() const noexcept {
        return swapchain.GetImageCount();
    }

private:
    void PresentThread(std::stop_token token);

    void CopyToSwapchain(Frame* frame);

    /// Records and submits the copy of frame to the currently acquired swapchain image.
    void SubmitToSwapchain(Frame* frame, bool wait_render_ready);

    /// Recreates the swapchain. On Android, with wait_for_new_surface the call blocks until a new
    /// surface has been provided via NotifySurfaceChanged; otherwise it uses whatever surface is
    /// current (adopting a pending one if there is one).
    void RecreateSwapchain(u32 width, u32 height, bool wait_for_new_surface);

    /// Returns true if a new surface has been set but the swapchain is still using the old one.
    bool HasPendingSurface();

    /// Waits until the frame's present_done fence is signaled. Returns false if timeout_ns
    /// elapsed first (only possible with a finite timeout).
    bool WaitPresentDone(Frame* frame, u64 timeout_ns = std::numeric_limits<u64>::max());

    /// Makes frame the retained "last presented" frame, releasing the previously retained one.
    void RetainPresentedFrame(Frame* frame);

    vk::RenderPass CreateRenderpass();

private:
    Frontend::EmuWindow& emu_window;
    const Instance& instance;
    Scheduler& scheduler;
    bool low_refresh_rate;
    vk::SurfaceKHR surface;
    vk::SurfaceKHR next_surface{};
    Swapchain swapchain;
    vk::CommandPool command_pool;
    vk::Queue graphics_queue;
    vk::RenderPass present_renderpass;
    std::vector<Frame> swap_chain;
    std::queue<Frame*> free_queue;
    /// The frame most recently copied to the swapchain. Kept out of free_queue so its
    /// contents stay intact and can be re-presented (see PresentLastFrame).
    Frame* last_presented_frame{};
    std::queue<Frame*> present_queue;
    std::condition_variable free_cv;
    std::condition_variable recreate_surface_cv;
    std::condition_variable_any frame_cv;
    std::mutex swapchain_mutex;
    std::mutex recreate_surface_mutex;
    std::mutex queue_mutex;
    std::mutex free_mutex;
    std::jthread present_thread;
    bool vsync_enabled{};
    bool blit_supported;
    bool use_present_thread{true};
    void* last_render_surface{};
};

} // namespace Vulkan
