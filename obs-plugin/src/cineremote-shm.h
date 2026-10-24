/* Shared-memory layout written by the CineRemote app (cineremote/obs_shm.py). */
#pragma once
#include <stdint.h>

#define BMCAM_MAGIC 0x52434D42u /* 'BMCR' */
#define BMCAM_VERSION 1u
#define BMCAM_MAX_SLOTS 8
#define BMCAM_HEADER_SIZE 4096u
#define BMCAM_AUDIO_RATE 48000u
#define BMCAM_AUDIO_CH 2u
#define BMCAM_AUDIO_RING_FRAMES (48000u * 2u)
#define BMCAM_AUDIO_BYTES (BMCAM_AUDIO_RING_FRAMES * BMCAM_AUDIO_CH * 2u)
#define BMCAM_MAX_W 3840u
#define BMCAM_MAX_H 3840u
#define BMCAM_MAX_PIXELS (BMCAM_MAX_W * BMCAM_MAX_H)
#define BMCAM_VIDEO_BUF_SIZE (BMCAM_MAX_PIXELS * 3u / 2u)
#define BMCAM_STALE_NS 3000000000ull /* heartbeat older than this = publisher gone */
#define BMCAM_TOTAL_SIZE ((uint64_t)BMCAM_HEADER_SIZE + BMCAM_AUDIO_BYTES + 3ull * BMCAM_VIDEO_BUF_SIZE)

#pragma pack(push, 1)
struct cineremote_vframe {
	uint32_t width, height, format; /* format 1 = I420 */
	uint32_t stride_y, stride_u, stride_v;
	uint64_t pts_ns;
	uint32_t data_size, pad;
};

struct cineremote_header {
	uint32_t magic, version;                        /* 0 */
	volatile uint32_t active, generation;           /* 8  generation: changes per publisher instance */
	volatile uint64_t heartbeat_ns;                 /* 16 */
	char name[128];                                 /* 24 */
	char cam_id[64];                                /* 152 */
	volatile uint64_t video_seq;                    /* 216 */
	volatile uint32_t video_index;                  /* 224 */
	volatile uint32_t color_range;                  /* 228  1 = limited (BT.709), 2 = full */
	struct cineremote_vframe vf[3];                      /* 232 */
	uint32_t audio_rate, audio_channels, audio_ring_frames, pad1; /* 352 */
	volatile uint64_t audio_write_frames;           /* 368 */
	volatile uint64_t audio_pts_ns;                 /* 376 */
};
#pragma pack(pop)

_Static_assert(sizeof(struct cineremote_vframe) == 40, "vframe size");
_Static_assert(sizeof(struct cineremote_header) == 384, "header size");
