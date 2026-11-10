package local.hdtvmate.tv;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTimestamp;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Low-latency live player: demuxes one program from 188-byte TS packets and feeds the hardware
 * decoders directly (no HTTP, no MediaPlayer buffering). Audio plays as soon as it is decoded and is
 * the master clock; video frames are shown at their PTS against it, late frames are dropped.
 *
 * Picture modes: Direct sends decoded frames straight to the screen's video layer (the box's own
 * deinterlacer and picture processing apply). The other modes route them through {@link Enhancer}.
 */
public final class LivePlayer {
    private static final String TAG = "HDTVMate";

    public interface Listener {
        /** enhanced: frames are drawn by the app at w x h (the screen surface should be that size). */
        void onVideoSize(int w, int h, boolean enhanced);
        void onFirstFrame(long msFromStart);
        void onLatency(long msArrivalToScreen);
    }

    private final Surface surface;
    private final Listener listener;
    private final int program;
    private final int picture;                        // Enhancer.DIRECT / CLEAN / SHARP / CLEAN_SHARP
    private final long startNs = System.nanoTime();
    private volatile boolean released;
    private boolean videoFailed, audioFailed;   // don't retry codec creation on every PES

    // demux
    private final Ts.Sections sections;
    private int pmtPid = -1, videoPid = -1, audioPid = -1;
    private String videoMime;
    private final Pes videoPes = new Pes(), audioPes = new Pes();

    // decoders
    private HandlerThread vThread, aThread;
    private Handler vh;
    // picture enhancement (all on the video thread): frames go to the GPU one at a time, each just before it is due
    private volatile Enhancer enh;
    private final ArrayDeque<long[]> outQ = new ArrayDeque<>();   // {output buffer index, ptsUs} waiting to be shown
    private boolean inFlight;                         // a frame was released to the Enhancer and is not drawn yet
    private long inFlightPts, frameNs = 16683333L;
    private int drawFails, srcW, srcH;
    private boolean interlaced;
    private float fps;
    private MediaCodec video, audio;
    private final ArrayDeque<Unit> vQueue = new ArrayDeque<>(), aQueue = new ArrayDeque<>();
    private final ArrayDeque<Integer> vFree = new ArrayDeque<>(), aFree = new ArrayDeque<>();
    private AudioTrack track;
    private int sampleRate = 48000, channels = 2;
    private long ptsBase = Long.MIN_VALUE;            // first PTS seen (90 kHz, unwrapped) -> time 0
    private long lastPts = -1, wrapAdd;
    private boolean firstFrameSent;
    // audio clock
    private long audioFirstUs = -1, audioFramesWritten;
    private volatile long anchorPtsUs = -1, anchorFrame;          // pts of the last written buffer and its first frame
    private final Map<Long, Long> aArrival = new java.util.LinkedHashMap<Long, Long>() {
        protected boolean removeEldestEntry(Map.Entry<Long, Long> e) { return size() > 200; }
    };
    private static final long MAX_AUDIO_AGE_NS = 1_000_000_000L; // only catch up when really behind (>1 s); dropping more often starves the track
    private final AudioTimestamp ts = new AudioTimestamp();
    // video wall clock when there is no audio yet
    private long vAnchorUs = -1, vAnchorNs;
    // latency stats
    private long latSum, latN, dropped, teiCount, ccGaps, packets, lastStatNs;
    private int vCc = -1, aCc = -1;

    private static final class Unit { byte[] data; long ptsUs; long arrivalNs; }

    private static final class Pes {
        byte[] buf = new byte[1 << 20];
        int len; boolean active; long arrivalNs;
        void reset() { len = 0; active = false; }
        void add(byte[] b, int off, int n) {
            if (len + n > buf.length) { byte[] nb = new byte[Math.max(buf.length * 2, len + n)]; System.arraycopy(buf, 0, nb, 0, len); buf = nb; }
            System.arraycopy(b, off, buf, len, n); len += n;
        }
    }

    public LivePlayer(Surface surface, int program, int picture, Listener l) {
        this.surface = surface;
        this.program = program;
        this.picture = picture;
        this.listener = l;
        sections = new Ts.Sections(new Ts.Sections.Handler() {
            public void section(int pid, byte[] s, int len) { onSection(pid, s, len); }
        });
        sections.watch(0);
    }

    // ================= demux (called from the USB stream thread) =================

    public void feed(byte[] b, int off, int len) {
        if (released) return;
        long now = System.nanoTime();
        if (now - lastStatNs > 2_000_000_000L) {
            if (lastStatNs != 0) Log.i(TAG, "stream: " + packets * 188 * 8 / 2 / 1000 + " kbit/s, missing-packet gaps " + ccGaps + ", reception errors " + teiCount);
            packets = 0; ccGaps = 0; teiCount = 0; lastStatNs = now;
        }
        for (int i = off; i + 188 <= off + len; i += 188) {
            if (b[i] != 0x47) continue;
            if ((b[i + 1] & 0x80) != 0) { teiCount++; continue; }             // demod-flagged reception error
            int pid = (b[i + 1] & 0x1f) << 8 | (b[i + 2] & 0xff);
            if ((pid == videoPid || pid == audioPid) && (b[i + 3] & 0x10) != 0) {
                int cc = b[i + 3] & 0x0f, last = pid == videoPid ? vCc : aCc;
                if (last >= 0 && cc != ((last + 1) & 0x0f) && cc != last) ccGaps++;
                if (pid == videoPid) vCc = cc; else aCc = cc;
            }
            packets++;
            if (pid == 0 || pid == pmtPid) { sections.packet(b, i); continue; }
            if (pid != videoPid && pid != audioPid) continue;
            int afc = (b[i + 3] >> 4) & 3;
            if ((afc & 1) == 0) continue;
            int p = 4;
            if (afc == 3) p = 5 + (b[i + 4] & 0xff);
            if (p >= 188) continue;
            boolean pusi = (b[i + 1] & 0x40) != 0;
            Pes pes = pid == videoPid ? videoPes : audioPes;
            if (pusi) {
                if (pes.active && pes.len > 0) pesDone(pid == videoPid, pes);
                pes.reset(); pes.active = true; pes.arrivalNs = now;
            }
            if (pes.active) pes.add(b, i + p, 188 - p);
        }
    }

    private void onSection(int pid, byte[] s, int len) {
        if (pid == 0) {
            Integer p = Ts.parsePat(s, len).get(program);
            if (p != null && p != pmtPid) { pmtPid = p; sections.watch(p); }
        } else if (pid == pmtPid && (s[0] & 0xff) == 2 && videoPid < 0) {
            int i = 12 + ((s[10] & 0x0f) << 8 | (s[11] & 0xff));
            while (i + 5 <= len - 4) {
                int type = s[i] & 0xff, epid = (s[i + 1] & 0x1f) << 8 | (s[i + 2] & 0xff);
                if (videoPid < 0 && (type == 0x02 || type == 0x01)) { videoPid = epid; videoMime = MediaFormat.MIMETYPE_VIDEO_MPEG2; }
                else if (videoPid < 0 && type == 0x1b) { videoPid = epid; videoMime = MediaFormat.MIMETYPE_VIDEO_AVC; }
                else if (audioPid < 0 && (type == 0x81 || type == 0x06)) audioPid = epid;
                i += 5 + ((s[i + 3] & 0x0f) << 8 | (s[i + 4] & 0xff));
            }
            Log.i(TAG, "program " + program + ": video pid " + videoPid + " " + videoMime + ", audio pid " + audioPid);
        }
    }

    private long unwrap(long pts) {
        if (lastPts >= 0 && pts + wrapAdd < lastPts - (1L << 32)) wrapAdd += 1L << 33;
        long v = pts + wrapAdd;
        lastPts = v;
        return v;
    }

    private void pesDone(boolean isVideo, Pes pes) {
        byte[] b = pes.buf;
        if (pes.len < 9 || b[0] != 0 || b[1] != 0 || b[2] != 1) return;
        int hlen = b[8] & 0xff, start = 9 + hlen;
        if (start >= pes.len) return;
        long pts = -1;
        if ((b[7] & 0x80) != 0) {
            pts = ((long) (b[9] >> 1) & 7) << 30 | (long) (b[10] & 0xff) << 22 | (long) ((b[11] & 0xff) >> 1) << 15
                    | (long) (b[12] & 0xff) << 7 | (b[13] & 0xff) >> 1;
            pts = unwrap(pts);
            if (ptsBase == Long.MIN_VALUE) ptsBase = pts;
        }
        if (pts < 0 || ptsBase == Long.MIN_VALUE) return;
        Unit u = new Unit();
        u.data = new byte[pes.len - start];
        System.arraycopy(b, start, u.data, 0, u.data.length);
        u.ptsUs = (pts - ptsBase) * 100 / 9;
        u.arrivalNs = pes.arrivalNs;
        if (isVideo) {
            if (video == null && (videoFailed || !startVideo(u.data))) return;     // wait for a sequence header / SPS
            synchronized (vQueue) { vQueue.add(u); }
            pumpVideo();
        } else {
            if (audio == null && (audioFailed || !startAudio(u.data))) return;
            splitAc3(u);
            pumpAudio();
        }
    }

    // AC-3 frames can straddle PES packets: rebuild whole sync frames before decoding
    private static final int[] AC3_WORDS_48K = {64,64,80,80,96,96,112,112,128,128,160,160,192,192,224,224,256,256,
            320,320,384,384,448,448,512,512,640,640,768,768,896,896,1024,1024,1152,1152,1280,1280};
    private byte[] ac3Buf = new byte[0];
    private long ac3Pts = -1;          // pts of the first byte in ac3Buf (us)

    private void splitAc3(Unit u) {
        byte[] nb = new byte[ac3Buf.length + u.data.length];
        System.arraycopy(ac3Buf, 0, nb, 0, ac3Buf.length);
        System.arraycopy(u.data, 0, nb, ac3Buf.length, u.data.length);
        if (ac3Buf.length == 0) ac3Pts = u.ptsUs;
        int i = 0;
        while (i + 6 <= nb.length) {
            if ((nb[i] & 0xff) != 0x0b || (nb[i + 1] & 0xff) != 0x77) { i++; continue; }
            int code = nb[i + 4] & 0x3f;
            if (code >= AC3_WORDS_48K.length) { i++; continue; }
            int size = AC3_WORDS_48K[code] * 2;
            if (i + size > nb.length) break;                   // incomplete: wait for the next PES
            Unit f = new Unit();
            f.data = new byte[size];
            System.arraycopy(nb, i, f.data, 0, size);
            f.ptsUs = ac3Pts;
            f.arrivalNs = u.arrivalNs;
            synchronized (aQueue) { aQueue.add(f); if (aQueue.size() > 400) aQueue.poll(); }
            ac3Pts += 32000;                                   // 1536 samples at 48 kHz
            i += size;
        }
        ac3Buf = java.util.Arrays.copyOfRange(nb, i, nb.length);
        if (ac3Buf.length == 0 || i > 0 && u.ptsUs > ac3Pts + 100000) ac3Pts = u.ptsUs;   // resync to PES pts
    }

    // ================= video =================

    private boolean startVideo(byte[] d) {
        int w = 1920, h = 1080;
        if (MediaFormat.MIMETYPE_VIDEO_MPEG2.equals(videoMime)) {
            int k = find(d, 0xb3);
            if (k < 0) return false;
            w = (d[k + 4] & 0xff) << 4 | (d[k + 5] & 0xff) >> 4;
            h = (d[k + 5] & 0x0f) << 8 | (d[k + 6] & 0xff);
            fps = FRAME_RATES[d[k + 7] & 0x0f];
            int e = find(d, 0xb5);                      // sequence extension: progressive_sequence
            interlaced = e >= 0 && (d[e + 4] & 0xf0) == 0x10 && (d[e + 5] & 0x08) == 0;
        } else {
            boolean sps = false;
            for (int i = 0; i + 4 < d.length && !sps; i++)
                if (d[i] == 0 && d[i + 1] == 0 && d[i + 2] == 1 && (d[i + 3] & 0x1f) == 7) sps = true;
            if (!sps) return false;
        }
        try {
            MediaFormat f = MediaFormat.createVideoFormat(videoMime, w, h);
            f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 2 << 20);
            f.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
            f.setInteger(MediaFormat.KEY_PRIORITY, 0);              // realtime
            MediaCodec vc = MediaCodec.createDecoderByType(videoMime);
            vThread = new HandlerThread("hdtvmate-video", android.os.Process.THREAD_PRIORITY_DISPLAY);
            vThread.start();
            vh = new Handler(vThread.getLooper());
            final Handler vh = this.vh;
            srcW = w; srcH = h;
            float shown = interlaced ? fps * 2 : fps;   // 1080i is shown as 59.94 pictures a second
            if (shown > 0) frameNs = (long) (1e9 / shown);
            if (picture != Enhancer.DIRECT) startEnhancer(w, h);
            video = vc;
            video.setCallback(new MediaCodec.Callback() {
                public void onInputBufferAvailable(MediaCodec c, int i) { synchronized (vQueue) { vFree.add(i); } pumpVideo(); }
                public void onOutputBufferAvailable(MediaCodec c, int i, MediaCodec.BufferInfo info) { renderVideo(c, i, info, vh); }
                public void onError(MediaCodec c, MediaCodec.CodecException e) { Log.e(TAG, "video codec", e); }
                public void onOutputFormatChanged(MediaCodec c, MediaFormat f) {
                    int ow = f.getInteger(MediaFormat.KEY_WIDTH), oh = f.getInteger(MediaFormat.KEY_HEIGHT);
                    if (f.containsKey("crop-right")) { ow = f.getInteger("crop-right") + 1 - f.getInteger("crop-left"); oh = f.getInteger("crop-bottom") + 1 - f.getInteger("crop-top"); }
                    srcW = ow; srcH = oh;
                    Enhancer en = enh;
                    if (en != null) en.setSource(ow, oh, interlaced);
                    listener.onVideoSize(ow, oh, en != null);
                }
            }, vh);
            Enhancer en = enh;
            video.configure(f, en != null ? en.input() : surface, null, 0);
            video.start();
            listener.onVideoSize(w, h, en != null);
            Log.i(TAG, "video decoder " + video.getName() + " " + w + "x" + h + (interlaced ? "i " : "p ") + fps + " fps, picture "
                    + Enhancer.NAMES[en != null ? picture : Enhancer.DIRECT]);
            hintDisplay(shown);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "start video", e);
            videoFailed = true;
            if (video != null) { video.release(); video = null; }
            Enhancer en = enh;
            enh = null;
            if (en != null) closeEnhancer(en);
            if (vThread != null) { vThread.quitSafely(); vThread = null; }
            return false;
        }
    }

    private static final float[] FRAME_RATES = {0, 24000 / 1001f, 24, 25, 30000 / 1001f, 30, 50, 60000 / 1001f, 60, 0, 0, 0, 0, 0, 0, 0};

    /**
     * Free quality in every mode: ask the display for the broadcast's exact rate (59.94 Hz, not 60) so there
     * is no repeated frame every ~17 s. Honoured when "Match content frame rate" is on in the box's settings.
     * Also logs the decoder's vendor parameters, which is where any chip-specific picture controls would show up.
     */
    private void hintDisplay(float shown) {
        try {
            if (Build.VERSION.SDK_INT >= 30 && shown > 0) surface.setFrameRate(shown, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE);
            if (Build.VERSION.SDK_INT >= 31) Log.i(TAG, "decoder vendor parameters: " + video.getSupportedVendorParameters());
        } catch (Exception e) { Log.w(TAG, "display hint", e); }
    }

    // ================= picture enhancement =================

    private void startEnhancer(final int w, final int h) {
        final Enhancer en;
        try { en = new Enhancer(surface, picture, w, h, interlaced); }
        catch (Exception e) { Log.e(TAG, "picture: unavailable, using Direct", e); return; }
        enh = en;
        en.texture().setOnFrameAvailableListener(new android.graphics.SurfaceTexture.OnFrameAvailableListener() {
            public void onFrameAvailable(android.graphics.SurfaceTexture t) { onFrame(); }
        }, vh);
        vh.post(new Runnable() { public void run() {
            try { en.init(); }
            catch (Exception e) { Log.e(TAG, "picture: GPU setup failed", e); fallBackToDirect(); }
        } });
    }

    /** Video thread. Give the screen back to the decoder; playback carries on in Direct mode. */
    private void fallBackToDirect() {
        Enhancer en = enh;
        if (en == null) return;
        enh = null;
        en.releaseGl();
        vh.removeCallbacks(pumpOutR);
        vh.removeCallbacks(stuckR);
        try {
            MediaCodec c = video;
            if (c != null && !released) {
                c.setOutputSurface(surface);
                for (long[] f : outQ) c.releaseOutputBuffer((int) f[0], false);
            }
        } catch (Exception e) { if (!released) Log.w(TAG, "picture: back to Direct", e); }
        outQ.clear();
        inFlight = false;
        en.release();
        Log.w(TAG, "picture: using Direct");
        if (!released) listener.onVideoSize(srcW, srcH, false);
    }

    private final Runnable pumpOutR = new Runnable() { public void run() { pumpOut(); } };
    private final Runnable stuckR = new Runnable() { public void run() { inFlight = false; pumpOut(); } };

    /**
     * Video thread. A SurfaceTexture keeps only the newest frame, so frames are handed over one at a time:
     * the next one is released ~25 ms before it is due and only after the previous one has been drawn.
     * Frames that are not due yet wait in the decoder's output buffers, as they do in Direct mode.
     */
    private void pumpOut() {
        vh.removeCallbacks(pumpOutR);
        MediaCodec c = video;
        while (!released && c != null && enh != null && !inFlight && !outQ.isEmpty()) {
            long[] f = outQ.peek();
            long now = System.nanoTime(), clock = clockUs(now);
            if (clock == Long.MIN_VALUE) { vAnchorUs = f[1]; vAnchorNs = now; clock = f[1]; }
            long delta = f[1] - clock;
            if (delta > 3000000) { vAnchorUs = f[1]; vAnchorNs = now; delta = 0; }      // discontinuity
            if (delta > 30000) { vh.postDelayed(pumpOutR, (delta - 25000) / 1000); return; }
            outQ.poll();
            try {
                if (delta < -80000) { c.releaseOutputBuffer((int) f[0], false); continue; }   // late: drop
                inFlight = true;
                inFlightPts = f[1];
                c.releaseOutputBuffer((int) f[0], true);
                vh.removeCallbacks(stuckR);
                vh.postDelayed(stuckR, 150);            // never wait forever for a frame the decoder swallowed
            } catch (Exception e) {
                inFlight = false;
                if (!released) Log.w(TAG, "video out", e);
            }
        }
    }

    /** Video thread: the released frame reached the SurfaceTexture. Filter it and queue it for its display time. */
    private void onFrame() {
        Enhancer en = enh;
        if (released || en == null) return;
        vh.removeCallbacks(stuckR);
        long now = System.nanoTime(), clock = clockUs(now);
        long delta = clock == Long.MIN_VALUE ? 0 : Math.max(0, Math.min(500000, inFlightPts - clock));
        try {
            en.draw(now + delta * 1000, frameNs);
            drawFails = 0;
            note(inFlightPts);
        } catch (Exception e) {
            if (released) return;
            Log.w(TAG, "picture: draw", e);
            if (++drawFails >= 5) { fallBackToDirect(); return; }
        }
        inFlight = false;
        pumpOut();
    }

    /** Any thread: tear the GL side down on the video thread and wait for it, so the screen surface is free again. */
    private void closeEnhancer(final Enhancer en) {
        Handler h = vh;
        HandlerThread t = vThread;
        if (h != null && t != null && t.isAlive() && Thread.currentThread() != t) {
            final CountDownLatch done = new CountDownLatch(1);
            if (h.post(new Runnable() { public void run() { en.releaseGl(); done.countDown(); } }))
                try { done.await(700, TimeUnit.MILLISECONDS); } catch (InterruptedException ignored) { }
        } else
            en.releaseGl();
        en.release();
    }

    private void pumpVideo() {
        while (true) {
            Unit u; int idx;
            synchronized (vQueue) {
                if (released || video == null || vQueue.isEmpty() || vFree.isEmpty()) return;
                u = vQueue.poll(); idx = vFree.poll();
            }
            try {
                ByteBuffer in = video.getInputBuffer(idx);
                int n = Math.min(in.capacity(), u.data.length);
                in.clear(); in.put(u.data, 0, n);
                video.queueInputBuffer(idx, 0, n, u.ptsUs, 0);
                synchronized (arrival) { arrival.put(u.ptsUs, u.arrivalNs); }
            } catch (Exception e) { if (!released) Log.w(TAG, "video in", e); return; }
        }
    }

    private final Map<Long, Long> arrival = new java.util.LinkedHashMap<Long, Long>() {
        protected boolean removeEldestEntry(Map.Entry<Long, Long> e) { return size() > 120; }
    };

    /** Current media time (us) from the audio clock, or a wall clock anchored at the first video frame. */
    private long clockUs(long nowNs) {
        AudioTrack t = track;
        if (t != null && anchorPtsUs >= 0 && t.getTimestamp(ts) && ts.framePosition > 0)
            return anchorPtsUs + (ts.framePosition - anchorFrame) * 1000000L / sampleRate + (nowNs - ts.nanoTime) / 1000;
        if (vAnchorUs < 0) return Long.MIN_VALUE;
        return vAnchorUs + (nowNs - vAnchorNs) / 1000;
    }

    private void renderVideo(final MediaCodec c, final int i, final MediaCodec.BufferInfo info, Handler h) {
        if (released) return;
        if (enh != null) { outQ.add(new long[]{i, info.presentationTimeUs}); pumpOut(); return; }
        long now = System.nanoTime(), clock = clockUs(now);
        if (clock == Long.MIN_VALUE) { vAnchorUs = info.presentationTimeUs; vAnchorNs = now; clock = info.presentationTimeUs; }
        long delta = info.presentationTimeUs - clock;
        try {
            if (delta < -80000) { c.releaseOutputBuffer(i, false); return; }        // late: drop, never fall behind
            if (delta > 3000000) { vAnchorUs = info.presentationTimeUs; vAnchorNs = now; delta = 0; }  // discontinuity
            if (delta > 500000) {                                                   // too early to hand over yet
                final long ptsUs = info.presentationTimeUs;
                h.postDelayed(new Runnable() { public void run() {
                    if (!released) try { c.releaseOutputBuffer(i, System.nanoTime() + 20000000L); note(ptsUs); } catch (Exception ignored) { }
                } }, delta / 1000 - 30);
                return;
            }
            c.releaseOutputBuffer(i, now + Math.max(0, delta) * 1000);
            note(info.presentationTimeUs);
        } catch (Exception e) { if (!released) Log.w(TAG, "video out", e); }
    }

    private void note(long ptsUs) {
        if (!firstFrameSent) { firstFrameSent = true; listener.onFirstFrame((System.nanoTime() - startNs) / 1000000); }
        Long arr;
        synchronized (arrival) { arr = arrival.remove(ptsUs); }
        if (arr != null) {
            latSum += (System.nanoTime() - arr) / 1000000; latN++;
            if (latN == 60) {
                listener.onLatency(latSum / latN);
                if (ageN > 0) Log.i(TAG, "audio age " + ageSumNs / ageN / 1000000 + " ms" + (fast ? " (catching up)" : ""));
                ageSumNs = 0; ageN = 0;
                latSum = 0; latN = 0; dropped = 0;
            }
        }
    }

    // ================= audio =================

    private boolean startAudio(byte[] d) {
        int k = -1;
        for (int i = 0; i + 6 < d.length; i++) if ((d[i] & 0xff) == 0x0b && (d[i + 1] & 0xff) == 0x77) { k = i; break; }
        if (k < 0) return false;
        int fscod = (d[k + 4] & 0xff) >> 6;
        sampleRate = fscod == 1 ? 44100 : fscod == 2 ? 32000 : 48000;
        try {
            MediaFormat f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AC3, sampleRate, 2);
            MediaCodec ac = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AC3);
            aThread = new HandlerThread("hdtvmate-audio", android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
            aThread.start();
            audio = ac;
            audio.setCallback(new MediaCodec.Callback() {
                public void onInputBufferAvailable(MediaCodec c, int i) { synchronized (aQueue) { aFree.add(i); } pumpAudio(); }
                public void onOutputBufferAvailable(MediaCodec c, int i, MediaCodec.BufferInfo info) { playAudio(c, i, info); }
                public void onError(MediaCodec c, MediaCodec.CodecException e) { Log.e(TAG, "audio codec", e); }
                public void onOutputFormatChanged(MediaCodec c, MediaFormat f) {
                    sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    makeTrack();
                }
            }, new Handler(aThread.getLooper()));
            audio.configure(f, null, null, 0);
            audio.start();
            Log.i(TAG, "audio decoder " + audio.getName() + " " + sampleRate + " Hz");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "start audio", e);
            audioFailed = true;
            if (audio != null) { audio.release(); audio = null; }
            if (aThread != null) { aThread.quitSafely(); aThread = null; }
            return false;
        }
    }

    private void pumpAudio() {
        while (true) {
            Unit u; int idx;
            synchronized (aQueue) {
                if (released || audio == null || aQueue.isEmpty() || aFree.isEmpty()) return;
                u = aQueue.poll(); idx = aFree.poll();
            }
            try {
                ByteBuffer in = audio.getInputBuffer(idx);
                int n = Math.min(in.capacity(), u.data.length);
                in.clear(); in.put(u.data, 0, n);
                audio.queueInputBuffer(idx, 0, n, u.ptsUs, 0);
                synchronized (aArrival) { aArrival.put(u.ptsUs, u.arrivalNs); }
            } catch (Exception e) { if (!released) Log.w(TAG, "audio in", e); return; }
        }
    }

    /** Drift control: play 2 % faster (same pitch) while audio is too far behind live; no dropped audio. */
    private boolean fast;
    private long ageSumNs, ageN;
    private void catchUp(long ageNs) {
        ageSumNs += ageNs; ageN++;
        boolean want = fast ? ageNs > 400_000_000L : ageNs > 550_000_000L;
        if (want == fast || track == null) return;
        try {
            track.setPlaybackParams(new android.media.PlaybackParams().setSpeed(want ? 1.02f : 1.0f).setPitch(1.0f));
            fast = want;
            Log.i(TAG, "audio " + (want ? "catching up (1.02x)" : "back to 1.0x") + ", age " + ageNs / 1000000 + " ms");
        } catch (Exception e) { Log.w(TAG, "playback speed", e); }
    }

    private void makeTrack() {
        if (track != null) { track.release(); track = null; }
        int mask = channels >= 6 ? AudioFormat.CHANNEL_OUT_5POINT1 : channels == 1 ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO;
        int min = AudioTrack.getMinBufferSize(sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT);
        track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
                .setAudioFormat(new AudioFormat.Builder().setSampleRate(sampleRate).setChannelMask(mask)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(Math.max(min * 2, sampleRate * 2 * channels * 250 / 1000))
                .setTransferMode(AudioTrack.MODE_STREAM).build();
        audioFirstUs = -1;
        audioFramesWritten = 0;
        anchorPtsUs = -1;
        track.play();
    }

    private void playAudio(MediaCodec c, int i, MediaCodec.BufferInfo info) {
        if (released) return;
        try {
            if (track == null) makeTrack();
            ByteBuffer out = c.getOutputBuffer(i);
            if (out != null && info.size > 0) {
                Long arr;
                synchronized (aArrival) { arr = aArrival.get(info.presentationTimeUs); }
                // live edge: anything that arrived more than 0.3 s ago (plus what is already queued in the
                // track) is stale -> drop it so playback catches up instead of staying behind forever
                long queuedNs = (audioFramesWritten - Math.max(0, track.getPlaybackHeadPosition())) * 1000000000L / sampleRate;
                if (arr != null) catchUp(System.nanoTime() - arr + queuedNs);
                {
                    if (audioFirstUs < 0) audioFirstUs = info.presentationTimeUs;
                    anchorPtsUs = info.presentationTimeUs;
                    anchorFrame = audioFramesWritten;
                    long frames = info.size / (2 * channels);
                    out.position(info.offset); out.limit(info.offset + info.size);
                    track.write(out, info.size, AudioTrack.WRITE_BLOCKING);
                    audioFramesWritten += frames;
                }
            }
            c.releaseOutputBuffer(i, false);
        } catch (Exception e) { if (!released) Log.w(TAG, "audio out", e); }
    }

    // ================= teardown =================

    public void release() {
        released = true;
        try { if (video != null) { video.stop(); video.release(); } } catch (Exception ignored) { }
        Enhancer en = enh;
        enh = null;
        if (en != null) closeEnhancer(en);
        try { if (audio != null) { audio.stop(); audio.release(); } } catch (Exception ignored) { }
        try { if (track != null) { track.pause(); track.flush(); track.release(); } } catch (Exception ignored) { }
        if (vThread != null) vThread.quitSafely();
        if (aThread != null) aThread.quitSafely();
    }

    private static int find(byte[] d, int code) {
        for (int i = 0; i + 7 < d.length; i++)
            if (d[i] == 0 && d[i + 1] == 0 && d[i + 2] == 1 && (d[i + 3] & 0xff) == code) return i;
        return -1;
    }
}
