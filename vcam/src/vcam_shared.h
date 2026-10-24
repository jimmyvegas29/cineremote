/* Shared definitions for the CineRemote virtual webcam.
 *
 * The media source runs inside the Windows Camera Frame Server service (session 0), so it creates
 * a Global\ file mapping + event (DACL: interactive users may only map read/write and signal) and the
 * app opens them. Must match cineremote/vcam.py.
 *
 * Mapping "Global\CineRemoteVCam_v2_s<N>" layout (little endian):
 *   0  u32 magic 'BMVC'   4  u32 version   8  u32 width   12 u32 height
 *   16 u64 seq           (app: incremented after each frame write)
 *   24 u64 writer_tick   (app: GetTickCount64 at last write; 0 = no valid frame)
 *   32 u64 reader_tick   (source: GetTickCount64 while a client is streaming)
 *   40 u32 buf_index     (app: buffer holding the newest frame)
 *   44 u32 reader_index  (source: buffer being copied right now, or VCAM_NO_BUFFER)
 *   VCAM_HEADER_SIZE..   VCAM_BUFFERS NV12 frames of VCAM_FRAME_SIZE (tightly packed, stride = width)
 * The writer never writes the buffer named by buf_index or reader_index, so frames can't tear.
 * Event "Global\CineRemoteVCam_v2_s<N>_evt": app signals after each frame so the source delivers at once.
 */
#pragma once
#include <stdint.h>

#define VCAM_SLOTS 4
#define VCAM_MAGIC 0x43564D42u /* 'BMVC' */
#define VCAM_VERSION 2u
#define VCAM_WIDTH 1920u
#define VCAM_HEIGHT 1080u
#define VCAM_FPS 30u
#define VCAM_BUFFERS 3u
#define VCAM_NO_BUFFER 0xFFFFFFFFu
#define VCAM_HEADER_SIZE 4096u
#define VCAM_FRAME_SIZE (VCAM_WIDTH * VCAM_HEIGHT * 3u / 2u)
#define VCAM_MAP_SIZE (VCAM_HEADER_SIZE + VCAM_BUFFERS * VCAM_FRAME_SIZE)
#define VCAM_MAP_NAME L"Global\\CineRemoteVCam_v2_s%d"
#define VCAM_EVT_NAME L"Global\\CineRemoteVCam_v2_s%d_evt"

#pragma pack(push, 1)
struct vcam_header {
	uint32_t magic, version, width, height;
	volatile uint64_t seq;
	volatile uint64_t writer_tick;
	volatile uint64_t reader_tick;
	volatile uint32_t buf_index;
	volatile uint32_t reader_index;
};
#pragma pack(pop)

/* CLSIDs of the 4 virtual camera slots (one COM class per slot so the source knows its slot). */
#define VCAM_CLSID_STR0 L"{98ECAD93-96D3-4645-9A5A-D0B60C18F786}"
#define VCAM_CLSID_STR1 L"{212806B8-9A5C-413A-81EB-E065649BC094}"
#define VCAM_CLSID_STR2 L"{D14DF0CE-27AE-47F2-82AE-92904FD154CE}"
#define VCAM_CLSID_STR3 L"{65F9C033-6985-4FB9-8E63-33A0F1716CD5}"
