package com.wincraft.client.audio;

import com.wincraft.client.render.AppWindowEntity;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.TargetDataLine;
import org.lwjgl.BufferUtils;
import org.lwjgl.openal.AL;
import org.lwjgl.openal.ALCCapabilities;
import org.lwjgl.openal.ALCapabilities;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.AL11;
import org.lwjgl.openal.ALC;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.EXTThreadLocalContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Multi-panel 3D spatial audio service.
 * Supports independent virtual audio cables for multiple panels concurrently.
 * Audio is downmixed to mono and spatialized with OpenAL.
 *
 * Spatial model: AL_LINEAR_DISTANCE_CLAMPED with reference=2 blocks, max=24 blocks.
 * Sound attenuates linearly from full volume at 2 m to silence at 24 m, and also
 * pans left/right based on the player's look direction relative to the panel — just
 * like a real screen in the room with you.
 *
 * Never records the user's real physical microphone.
 */
public final class SpatialAudioService {
    private static final Logger LOG = LoggerFactory.getLogger("wincraft/audio");
    private static final AudioFormat FORMAT = new AudioFormat(48_000f, 16, 2, true, false);
    private static final int FRAMES = 960; // 20 ms
    private static final int BUFFER_COUNT = 6;

    /** Reference distance in blocks: full volume when closer than this. */
    private static final float REF_DISTANCE  = 2.0f;
    /** Maximum distance in blocks: silence beyond this. */
    private static final float MAX_DISTANCE  = 24.0f;
    /** Default gain (0..2). 1.0 = unity. */
    private static final float DEFAULT_GAIN  = 1.0f;

    private static final class PanelStream {
        final AppWindowEntity panel;
        final Mixer.Info mixer;
        final AtomicBoolean running = new AtomicBoolean(true);
        volatile TargetDataLine line;
        volatile int source;
        final int[] buffers = new int[BUFFER_COUNT];
        volatile String status;
        volatile float gain = DEFAULT_GAIN;
        Thread worker;

        PanelStream(AppWindowEntity panel, Mixer.Info mixer) {
            this.panel = panel;
            this.mixer = mixer;
            this.status = "Initializing " + mixer.getName();
        }
    }

    private final Map<AppWindowEntity, PanelStream> streams = new ConcurrentHashMap<>();
    private long alDevice = 0, alContext = 0;
    private ALCCapabilities alcCapabilities;
    private ALCapabilities alCapabilities;
    private final Object alLock = new Object();
    private volatile String globalStatus = "3D audio idle";

    /** Restores the Minecraft/OpenAL context after WinCraft's 3D audio calls. */
    private final class ContextScope implements AutoCloseable {
        private final long previousContext;
        private final ALCCapabilities previousAlcCapabilities;
        private final ALCapabilities previousAlCapabilities;

        ContextScope(long previousContext, ALCCapabilities previousAlcCapabilities,
                     ALCapabilities previousAlCapabilities) {
            this.previousContext = previousContext;
            this.previousAlcCapabilities = previousAlcCapabilities;
            this.previousAlCapabilities = previousAlCapabilities;
        }

        @Override public void close() {
            EXTThreadLocalContext.alcSetThreadContext(previousContext);
            ALC.setCapabilities(previousAlcCapabilities);
            AL.setCurrentThread(previousAlCapabilities);
        }
    }

    public static List<Mixer.Info> inputMixers() {
        List<Mixer.Info> virtualCables = new ArrayList<>();
        List<Mixer.Info> otherInputs = new ArrayList<>();
        var wanted = new DataLine.Info(TargetDataLine.class, FORMAT);
        for (Mixer.Info info : AudioSystem.getMixerInfo()) {
            try {
                if (!AudioSystem.getMixer(info).isLineSupported(wanted)) continue;
                String name = info.getName().toLowerCase(java.util.Locale.ROOT);
                if (name.contains("cable output") || name.contains("virtual cable") ||
                    name.contains("voicemeeter") || name.contains("line (vb-audio") ||
                    name.contains("virtual audio") || name.contains("stereo mix") ||
                    name.contains("what u hear") || name.contains("wave out")) {
                    virtualCables.add(info);
                } else {
                    otherInputs.add(info);
                }
            } catch (RuntimeException ignored) { }
        }
        List<Mixer.Info> combined = new ArrayList<>(virtualCables);
        combined.addAll(otherInputs);
        return combined;
    }

    public String status() { return globalStatus; }

    public String statusFor(AppWindowEntity p) {
        PanelStream s = streams.get(p);
        return s != null ? s.status : "No 3D audio";
    }

    public Mixer.Info mixerFor(AppWindowEntity p) {
        PanelStream s = streams.get(p);
        return s != null ? s.mixer : null;
    }

    public boolean activeFor(AppWindowEntity p) { return streams.containsKey(p); }

    public List<AppWindowEntity> activePanels() {
        return new ArrayList<>(streams.keySet());
    }

    public float gainFor(AppWindowEntity p) {
        PanelStream s = streams.get(p);
        return s != null ? s.gain : DEFAULT_GAIN;
    }

    public void setGain(AppWindowEntity p, float gain) {
        PanelStream s = streams.get(p);
        if (s == null) return;
        float clamped = Math.clamp(gain, 0f, 2f);
        s.gain = clamped;
        synchronized (alLock) {
            if (alContext == 0 || s.source == 0) return;
            try (ContextScope ignored = enterOpenALContext()) {
                AL10.alSourcef(s.source, AL10.AL_GAIN, clamped);
            }
        }
    }

    public synchronized void start(AppWindowEntity panel, Mixer.Info mixer) {
        if (panel == null || mixer == null) throw new IllegalArgumentException("Panel and capture input required");

        // Stop existing stream for this panel if any
        stop(panel);

        // Check if mixer is already used by another panel
        for (PanelStream existing : streams.values()) {
            if (existing.mixer.getName().equalsIgnoreCase(mixer.getName())) {
                throw new IllegalArgumentException("This virtual cable is already assigned to " +
                    existing.panel.info.title() + ". Choose a different cable for each panel.");
            }
        }

        ensureOpenAL();

        PanelStream stream = new PanelStream(panel, mixer);
        streams.put(panel, stream);
        globalStatus = "3D audio active (" + streams.size() + " panel" + (streams.size() > 1 ? "s" : "") + ")";

        stream.worker = Thread.ofPlatform().name("WinCraft 3D Audio - " + panel.info.title()).daemon(true).start(() -> {
            runStream(stream);
        });
    }

    public synchronized void stop(AppWindowEntity panel) {
        if (panel == null) return;
        PanelStream s = streams.get(panel);
        if (s != null) {
            s.running.set(false);
            if (s.line != null) {
                try { s.line.stop(); s.line.close(); } catch (RuntimeException ignored) { }
            }
            if (s.worker != null && s.worker != Thread.currentThread()) {
                try { s.worker.join(5000); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                if (s.worker.isAlive()) {
                    s.status = "Waiting for the audio device to stop";
                    globalStatus = s.status;
                    return;
                }
            }
            streams.remove(panel, s);
            synchronized (alLock) {
                if (alContext != 0) {
                    try (ContextScope ignored = enterOpenALContext()) {
                        if (s.source != 0) {
                            AL10.alSourceStop(s.source);
                            AL10.alDeleteSources(s.source);
                            s.source = 0;
                        }
                        for (int i = 0; i < s.buffers.length; i++) {
                            if (s.buffers[i] != 0) {
                                AL10.alDeleteBuffers(s.buffers[i]);
                                s.buffers[i] = 0;
                            }
                        }
                    } catch (RuntimeException e) {
                        LOG.warn("Could not release spatial audio resources for '{}': {}", s.panel.info.title(), e.toString());
                    }
                }
            }
        }
        if (streams.isEmpty()) {
            teardownOpenAL();
            globalStatus = "3D audio off";
        } else {
            globalStatus = "3D audio active (" + streams.size() + " panel" + (streams.size() > 1 ? "s" : "") + ")";
        }
    }

    public synchronized void stop() {
        stopAll();
    }

    public synchronized void stopAll() {
        for (AppWindowEntity p : new ArrayList<>(streams.keySet())) {
            stop(p);
        }
    }

    public void stopIfPanel(AppWindowEntity p) { stop(p); }

    /**
     * Called every frame/tick to update listener and source spatial audio positions.
     *
     * @param panel  the panel whose audio source to reposition
     * @param sx,sy,sz  world position of the panel (audio source)
     * @param lx,ly,lz  player eye position (listener)
     * @param fx,fy,fz  player look-direction unit vector (listener "forward" vector)
     * @param ux,uy,uz  player head up vector (listener "up" vector)
     */
    public void update(AppWindowEntity panel, float sx, float sy, float sz,
                       float lx, float ly, float lz,
                       float fx, float fy, float fz,
                       float ux, float uy, float uz) {
        PanelStream s = streams.get(panel);
        if (s == null || s.source == 0) return;
        synchronized (alLock) {
            if (alContext == 0) return;
            try (ContextScope ignored = enterOpenALContext()) {
                // Listener position (player eye / camera)
                AL10.alListener3f(AL10.AL_POSITION, lx, ly, lz);
                AL10.alListener3f(AL10.AL_VELOCITY, 0f, 0f, 0f);
                // Listener orientation: forward (at) + up
                AL10.alListenerfv(AL10.AL_ORIENTATION, new float[]{ fx, fy, fz, ux, uy, uz });
                // Audio source at the panel's world centre
                AL10.alSource3f(s.source, AL10.AL_POSITION, sx, sy, sz);
                AL10.alSource3f(s.source, AL10.AL_VELOCITY, 0f, 0f, 0f);
            }
        }
    }

    public void update(AppWindowEntity panel, float sx, float sy, float sz,
                       float lx, float ly, float lz,
                       float fx, float fy, float fz) {
        update(panel, sx, sy, sz, lx, ly, lz, fx, fy, fz, 0f, 1f, 0f);
    }

    private void ensureOpenAL() {
        synchronized (alLock) {
            if (alContext != 0) return;
            alDevice = ALC10.alcOpenDevice((ByteBuffer)null);
            if (alDevice == 0) throw new IllegalStateException("OpenAL output device unavailable");
            alContext = ALC10.alcCreateContext(alDevice, (java.nio.IntBuffer)null);
            if (alContext == 0) {
                ALC10.alcCloseDevice(alDevice);
                alDevice = 0;
                throw new IllegalStateException("Could not create the spatial audio OpenAL context");
            }
            alcCapabilities = ALC.createCapabilities(alDevice);
            if (!alcCapabilities.ALC_EXT_thread_local_context) {
                ALC10.alcDestroyContext(alContext);
                ALC10.alcCloseDevice(alDevice);
                alContext = 0;
                alDevice = 0;
                alcCapabilities = null;
                throw new IllegalStateException("This OpenAL device does not support thread-local contexts required for safe 3D audio");
            }

            ALCCapabilities previousAlc = ALC.getCapabilities();
            ALCapabilities previousAl = AL.getCapabilities();
            long previousContext = 0;
            boolean savedPreviousContext = false;
            Throwable initializationFailure = null;
            try {
                ALC.setCapabilities(alcCapabilities);
                previousContext = EXTThreadLocalContext.alcGetThreadContext();
                savedPreviousContext = true;
                if (!EXTThreadLocalContext.alcSetThreadContext(alContext)) {
                    throw new IllegalStateException("Could not activate the WinCraft OpenAL context");
                }
                alCapabilities = AL.createCapabilities(alcCapabilities);
            } catch (RuntimeException | LinkageError e) {
                initializationFailure = e;
            } finally {
                if (savedPreviousContext) {
                    ALC.setCapabilities(alcCapabilities);
                    EXTThreadLocalContext.alcSetThreadContext(previousContext);
                }
                ALC.setCapabilities(previousAlc);
                AL.setCurrentThread(previousAl);
            }
            if (initializationFailure != null) {
                ALC10.alcDestroyContext(alContext);
                ALC10.alcCloseDevice(alDevice);
                alContext = 0;
                alDevice = 0;
                alcCapabilities = null;
                alCapabilities = null;
                if (initializationFailure instanceof RuntimeException runtime) throw runtime;
                throw new IllegalStateException("Could not initialize 3D audio on this OpenAL device", initializationFailure);
            }
        }
    }

    private ContextScope enterOpenALContext() {
        if (alContext == 0 || alcCapabilities == null || alCapabilities == null) {
            throw new IllegalStateException("Spatial audio OpenAL context is not ready");
        }
        ALCCapabilities previousAlc = ALC.getCapabilities();
        ALCapabilities previousAl = AL.getCapabilities();
        ALC.setCapabilities(alcCapabilities);
        long previousContext = EXTThreadLocalContext.alcGetThreadContext();
        if (!EXTThreadLocalContext.alcSetThreadContext(alContext)) {
            ALC.setCapabilities(previousAlc);
            AL.setCurrentThread(previousAl);
            throw new IllegalStateException("Could not activate the WinCraft OpenAL context on this thread");
        }
        AL.setCurrentThread(alCapabilities);
        return new ContextScope(previousContext, previousAlc, previousAl);
    }

    private void teardownOpenAL() {
        synchronized (alLock) {
            if (alContext != 0) {
                ALC10.alcDestroyContext(alContext);
                alContext = 0;
            }
            if (alDevice != 0) {
                ALC10.alcCloseDevice(alDevice);
                alDevice = 0;
            }
            alcCapabilities = null;
            alCapabilities = null;
        }
    }

    private void runStream(PanelStream s) {
        try {
            var input = AudioSystem.getMixer(s.mixer);
            TargetDataLine line = (TargetDataLine) input.getLine(new DataLine.Info(TargetDataLine.class, FORMAT));
            line.open(FORMAT, FRAMES * FORMAT.getFrameSize() * BUFFER_COUNT);
            s.line = line;
            line.start();

            ArrayDeque<Integer> free = new ArrayDeque<>();
            try (ContextScope ignored = enterOpenALContext()) {
                synchronized (alLock) {
                    if (!s.running.get()) return;
                    s.source = AL10.alGenSources();
                    // Absolute world-space source — NOT relative to listener
                    AL10.alSourcei(s.source, AL10.AL_SOURCE_RELATIVE, AL10.AL_FALSE);
                    // Linear distance model: full volume at REF_DISTANCE, silence at MAX_DISTANCE
                    AL10.alDistanceModel(AL11.AL_LINEAR_DISTANCE_CLAMPED);
                    AL10.alSourcef(s.source, AL10.AL_REFERENCE_DISTANCE, REF_DISTANCE);
                    AL10.alSourcef(s.source, AL10.AL_MAX_DISTANCE,       MAX_DISTANCE);
                    AL10.alSourcef(s.source, AL10.AL_ROLLOFF_FACTOR,     1.0f);
                    AL10.alSourcef(s.source, AL10.AL_GAIN,               s.gain);
                    AL10.alSource3f(s.source, AL10.AL_VELOCITY,          0f, 0f, 0f);
                    // Omnidirectional point source (no cone narrowing)
                    AL10.alSourcef(s.source, AL10.AL_CONE_INNER_ANGLE,   360.0f);
                    AL10.alSourcef(s.source, AL10.AL_CONE_OUTER_ANGLE,   360.0f);
                    for (int i = 0; i < BUFFER_COUNT; i++) {
                        s.buffers[i] = AL10.alGenBuffers();
                        free.add(s.buffers[i]);
                    }
                }

                s.status = "Playing 3D: " + s.mixer.getName() + " -> " + s.panel.info.title();
                byte[] stereo = new byte[FRAMES * FORMAT.getFrameSize()];
                ByteBuffer mono = BufferUtils.createByteBuffer(FRAMES * 2).order(ByteOrder.LITTLE_ENDIAN);

                while (s.running.get()) {
                    synchronized (alLock) {
                        if (s.source == 0) break;
                        int processed = AL10.alGetSourcei(s.source, AL10.AL_BUFFERS_PROCESSED);
                        while (processed-- > 0) free.add(AL10.alSourceUnqueueBuffers(s.source));
                    }

                    int bytes = line.read(stereo, 0, stereo.length);
                    if (bytes < 4 || !s.running.get()) continue;
                    if (free.isEmpty()) continue; // Drop frames to prevent latency lag

                    mono.clear();
                    for (int i = 0; i + 3 < bytes && mono.remaining() >= 2; i += 4) {
                        short left = (short)((stereo[i] & 255) | (stereo[i+1] << 8));
                        short right = (short)((stereo[i+2] & 255) | (stereo[i+3] << 8));
                        mono.putShort((short)(((int)left + (int)right) / 2));
                    }
                    mono.flip();
                    if (!mono.hasRemaining()) continue;

                    int buffer = free.removeFirst();
                    synchronized (alLock) {
                        if (s.source == 0) break;
                        AL10.alBufferData(buffer, AL10.AL_FORMAT_MONO16, mono, (int) FORMAT.getSampleRate());
                        AL10.alSourceQueueBuffers(s.source, buffer);
                        if (AL10.alGetSourcei(s.source, AL10.AL_SOURCE_STATE) != AL10.AL_PLAYING &&
                            AL10.alGetSourcei(s.source, AL10.AL_BUFFERS_QUEUED) >= 2) {
                            AL10.alSourcePlay(s.source);
                        }
                    }
                }
            }
        } catch (Exception | LinkageError e) {
            s.status = "Error: " + e.getMessage();
            LOG.warn("Spatial audio stream error for '{}': {}", s.panel.info.title(), e.toString());
        } finally {
            if (s.line != null) {
                try { s.line.stop(); s.line.close(); } catch (RuntimeException ignored) { }
            }
        }
    }
}
