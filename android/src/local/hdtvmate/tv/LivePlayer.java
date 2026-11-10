package local.hdtvmate.tv;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTimestamp;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Map;

/**
 * Low-latency live player: demuxes one program from 188-byte TS packets and feeds the hardware
 * decoders directly (no HTTP, no MediaPlayer buffering). Audio plays as soon as it is decoded and is
 * the master clock; video frames are shown at their PTS against it, late frames are dropped.
 */
public final class LivePlayer {
    private static final String TAG = "HDTVMate";

    public interface Listener {
        void onVideoSize(int w, int h);
        void onFirstFrame(long msFromStart);
        void onLatency(long msArrivalToScreen);
    }

    private final Surface surface;
    private final Listener listener;
    private final int program;
    private final long startNs = System.nanoTime();
    private volatile boolean released;

    // demux
    private final Ts.Sections sections;
    private int pmtPid = -1, videoPid = -1, audioPid = -1;
    private String videoMime;
    private final Pes videoPes = new Pes(), audioPes = new Pes();

    // decoders
    private HandlerThread vThread, aThread;
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
    private static final long MAX_AUDIO_AGE_NS = 200_000_000L;   // keep audio within 0.2 s of arrival (live edge)
    private final AudioTimestamp ts = new AudioTimestamp();
    // video wall clock when there is no audio yet
    private long vAnchorUs = -1, vAnchorNs;
    // latency stats
    private long latSum, latN, dropped;

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

    public LivePlayer(Surface surface, int program, Listener l) {
        this.surface = surface;
        this.program = program;
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
        for (int i = off; i + 188 <= off + len; i += 188) {
            if (b[i] != 0x47 || (b[i + 1] & 0x80) != 0) continue;           // sync / transport error
            int pid = (b[i + 1] & 0x1f) << 8 | (b[i + 2] & 0xff);
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
            if (video == null && !startVideo(u.data)) return;     // wait for a sequence header / SPS
            synchronized (vQueue) { vQueue.add(u); }
            pumpVideo();
        } else {
            if (audio == null && !startAudio(u.data)) return;
            synchronized (aQueue) { aQueue.add(u); if (aQueue.size() > 50) aQueue.poll(); }
            pumpAudio();
        }
    }

    // ================= video =================

    private boolean startVideo(byte[] d) {
        int w = 1920, h = 1080;
        if (MediaFormat.MIMETYPE_VIDEO_MPEG2.equals(videoMime)) {
            int k = find(d, 0xb3);
            if (k < 0) return false;
            w = (d[k + 4] & 0xff) << 4 | (d[k + 5] & 0xff) >> 4;
            h = (d[k + 5] & 0x0f) << 8 | (d[k + 6] & 0xff);
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
            vThread = new HandlerThread("hdtvmate-video", android.os.Process.THREAD_PRIORITY_DISPLAY);
            vThread.start();
            final Handler vh = new Handler(vThread.getLooper());
            video = MediaCodec.createDecoderByType(videoMime);
            video.setCallback(new MediaCodec.Callback() {
                public void onInputBufferAvailable(MediaCodec c, int i) { synchronized (vQueue) { vFree.add(i); } pumpVideo(); }
                public void onOutputBufferAvailable(MediaCodec c, int i, MediaCodec.BufferInfo info) { renderVideo(c, i, info, vh); }
                public void onError(MediaCodec c, MediaCodec.CodecException e) { Log.e(TAG, "video codec", e); }
                public void onOutputFormatChanged(MediaCodec c, MediaFormat f) {
                    int ow = f.getInteger(MediaFormat.KEY_WIDTH), oh = f.getInteger(MediaFormat.KEY_HEIGHT);
                    if (f.containsKey("crop-right")) { ow = f.getInteger("crop-right") + 1 - f.getInteger("crop-left"); oh = f.getInteger("crop-bottom") + 1 - f.getInteger("crop-top"); }
                    listener.onVideoSize(ow, oh);
                }
            }, vh);
            video.configure(f, surface, null, 0);
            video.start();
            listener.onVideoSize(w, h);
            Log.i(TAG, "video decoder " + video.getName() + " " + w + "x" + h);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "start video", e);
            return false;
        }
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
                if (dropped > 0) Log.i(TAG, "audio: dropped " + dropped + " stale buffers to stay live");
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
            aThread = new HandlerThread("hdtvmate-audio", android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
            aThread.start();
            audio = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AC3);
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

    private void makeTrack() {
        if (track != null) { track.release(); track = null; }
        int mask = channels >= 6 ? AudioFormat.CHANNEL_OUT_5POINT1 : channels == 1 ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO;
        int min = AudioTrack.getMinBufferSize(sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT);
        track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
                .setAudioFormat(new AudioFormat.Builder().setSampleRate(sampleRate).setChannelMask(mask)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(min)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
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
                if (arr != null && System.nanoTime() - arr + queuedNs > MAX_AUDIO_AGE_NS && anchorPtsUs >= 0) {
                    dropped++;
                } else {
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
