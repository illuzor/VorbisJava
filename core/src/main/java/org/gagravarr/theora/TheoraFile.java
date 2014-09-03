/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.gagravarr.theora;

import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.gagravarr.ogg.HighLevelOggStreamPacket;
import org.gagravarr.ogg.OggFile;
import org.gagravarr.ogg.OggPacket;
import org.gagravarr.ogg.OggPacketReader;
import org.gagravarr.ogg.OggPacketWriter;
import org.gagravarr.ogg.OggStreamAudioData;
import org.gagravarr.ogg.audio.OggAudioHeaders;
import org.gagravarr.ogg.audio.OggAudioStreamHeaders;
import org.gagravarr.skeleton.SkeletonFisbone;
import org.gagravarr.skeleton.SkeletonKeyFramePacket;
import org.gagravarr.skeleton.SkeletonPacketFactory;
import org.gagravarr.skeleton.SkeletonStream;

/**
 * This is a wrapper around an OggFile that lets you
 *  get at all the interesting bits of a Theora file.
 */
public class TheoraFile extends HighLevelOggStreamPacket implements Closeable {
    private OggFile ogg;
    private OggPacketReader r;
    private OggPacketWriter w;
    private int sid = -1;

    private TheoraInfo info;
    private TheoraComments comments;
    private TheoraSetup setup;

    private SkeletonStream skeleton;
    private Map<Integer,OggAudioStreamHeaders> soundtracks;

    private LinkedList<OggStreamAudioData> pendingPackets;
    private List<TheoraVideoData> writtenPackets;

    /**
     * Opens the given file for reading
     */
    public TheoraFile(File f) throws IOException, FileNotFoundException {
        this(new OggFile(new FileInputStream(f)));
    }
    /**
     * Opens the given file for reading
     */
    public TheoraFile(OggFile ogg) throws IOException {
        this(ogg.getPacketReader());
        this.ogg = ogg;
    }
    /**
     * Loads a Theora File from the given packet reader.
     */
    public TheoraFile(OggPacketReader r) throws IOException {
        this.r = r;
        this.pendingPackets = new LinkedList<OggStreamAudioData>();
        this.soundtracks = new HashMap<Integer, OggAudioStreamHeaders>();

        Set<Integer> headerCompleteSoundtracks = new HashSet<Integer>();

        // The start of the file should contain the skeleton
        //  (if there is one), the header packets for the Theora
        //  stream, the header packets for the soundtrack streams,
        //  and the start of any other streams that are time-parallel
        // However, they can all be in pretty much any order, including
        //  coming after the start of the first few video frames, so
        //  process into the video a little bit looking for things
        //  we care about
        int packetsSinceSetup = -1;
        OggPacket p = null;
        while( (p = r.getNextPacket()) != null ) {
            int psid = p.getSid();
            if (p.isBeginningOfStream() && p.getData().length > 10) {
                if (TheoraPacketFactory.isTheoraStream(p)) {
                    sid = psid;
                    info = (TheoraInfo)TheoraPacketFactory.create(p);
                } else if (SkeletonPacketFactory.isSkeletonStream(p)) {
                    skeleton = new SkeletonStream(p);
                } else {
                    try {
                        soundtracks.put(psid, OggAudioStreamHeaders.create(p));
                    } catch (IllegalArgumentException e) {
                        // Not a soundtrack
                    }
                }
            } else {
                if (psid == sid) {
                    TheoraPacket tp = TheoraPacketFactory.create(p);

                    // First three packets must be info, comments, setup
                    if (comments == null) {
                        comments = (TheoraComments)tp;
                    } else if (setup == null) {
                        setup = (TheoraSetup)tp;
                        packetsSinceSetup = 0;
                    } else {
                        pendingPackets.add((TheoraVideoData)tp);
                        packetsSinceSetup++;

                        // Are we, in all likelyhood, past all the headers?
                        if (packetsSinceSetup > 10) break;
                    }
                } else if (skeleton != null && skeleton.getSid() == psid) {
                    skeleton.processPacket(p);
                } else if (soundtracks.containsKey(psid)) {
                    if (headerCompleteSoundtracks.contains(psid)) {
                        // Onto the data part for this soundtrack
                        pendingPackets.add(null); // TODO Wrap the soundtrack packet
                    } else {
                        boolean ongoing = soundtracks.get(psid).populate(p);
                        if (! ongoing) {
                            headerCompleteSoundtracks.add(psid);
                        }
                    }
                }
            }
        }
        if (sid == -1) {
            throw new IllegalArgumentException("Supplied File is not Theora");
        }
    }

    /**
     * Opens for writing.
     */
    public TheoraFile(OutputStream out) {
        this(out, new TheoraInfo(), new TheoraComments(), new TheoraSetup());
    }
    /**
     * Opens for writing, based on the settings
     *  from a pre-read file. The Steam ID (SID) is
     *  automatically allocated for you.
     */
    public TheoraFile(OutputStream out, TheoraInfo info, TheoraComments comments, TheoraSetup setup) {
        this(out, -1, info, comments, setup);
    }
    /**
     * Opens for writing, based on the settings
     *  from a pre-read file, with a specific
     *  Steam ID (SID). You should only set the SID
     *  when copying one file to another!
     */
    public TheoraFile(OutputStream out, int sid, TheoraInfo info, TheoraComments comments, TheoraSetup setup) {
        ogg = new OggFile(out);

        if(sid > 0) {
            w = ogg.getPacketWriter(sid);
            this.sid = sid;
        } else {
            w = ogg.getPacketWriter();
            this.sid = w.getSid();
        }

        this.writtenPackets = new ArrayList<TheoraVideoData>();
        this.soundtracks = new HashMap<Integer, OggAudioStreamHeaders>();

        this.info = info;
        this.comments = comments;
        this.setup = setup;
    }

    /**
     * Returns the Ogg Stream ID
     */
    public int getSid() {
        return sid;
    }

    public TheoraInfo getInfo() {
        return info;
    }
    public TheoraComments getComments() {
        return comments;
    }
    public TheoraSetup getSetup() {
        return setup;
    }

    /**
     * Returns the Skeleton data describing all the
     *  streams, or null if the file has no Skeleton stream
     */
    public SkeletonStream getSkeleton() {
        return skeleton;
    }
    public void ensureSkeleton() {
        if (skeleton != null) return;

        List<Integer> sids = new ArrayList<Integer>();
        if (sid != -1) {
            sids.add(sid);
        }
        for (Integer stsid : soundtracks.keySet()) {
            if (stsid != -1) {
                sids.add(stsid);
            }
        }
        int[] sidsA = new int[sids.size()];
        for (int i=0; i<sidsA.length; i++) {
            sidsA[i] = sids.get(i);
        }

        skeleton = new SkeletonStream(sidsA);
    }

    /**
     * Returns the soundtracks and their stream IDs
     */
    public Map<Integer, ? extends OggAudioHeaders> getSoundtrackStreams() {
        return soundtracks;
    }
    /**
     * Returns all the soundtracks
     */
    public Collection<? extends OggAudioHeaders> getSoundtracks() {
        return soundtracks.values();
    }
    /**
     * Adds a new soundtrack to the video
     */
    public void addSoundtrack(OggAudioHeaders audio) {
        if (w == null) {
            throw new IllegalStateException("Not in write mode");
        }

        // If it doesn't have a sid yet, get it one
        if (audio.getSid() == -1) {
            // TODO How?
        }

        // If we have a skeleton, tell it about the new stream
        if (skeleton != null) {
            SkeletonFisbone bone = skeleton.addBoneForStream(audio.getSid());
            bone.setContentType(audio.getType().mimetype);
            // TODO Populate the rest of the bone as best we can
        }

        // Record the new audio stream
        soundtracks.put(audio.getSid(), (OggAudioStreamHeaders)audio);
    }

    /**
     * Returns the next audio or video packet across
     *  any supported stream, or null if no more remain
     */
    public OggStreamAudioData getNextAudioVideoPacket() throws IOException {
        return getNextAudioVideoPacket(null);
    }
    /**
     * Returns the next audio or video packet from any of
     *  the specified streams, or null if no more remain
     */
    public OggStreamAudioData getNextAudioVideoPacket(Set<Integer> sids) throws IOException {
        OggStreamAudioData data = null;

        if (! pendingPackets.isEmpty()) {
            data = pendingPackets.removeFirst();
        }

        if (data == null) {
            OggPacket p = null;
            while ((p = r.getNextPacket()) != null) {
                if (sids == null || sids.contains(p.getSid())) {
                    if (p.getSid() == sid) {
                        data = (OggStreamAudioData)TheoraPacketFactory.create(p);
                        break;
                    } else if (soundtracks.containsKey(p.getSid())) {
                        // TODO How do we create the soundtrack packet?
                    } else {
                        // We don't know how to handle this stream
                        throw new IllegalArgumentException("Unsupported stream type with sid " + p.getSid());
                    }
                } else {
                    // They're not interested in this stream
                    // Proceed on to the next packet
                }
            }
        }

        return data;
    }


    /**
     * Buffers the given video ready for writing
     *  out. Data won't be written out yet, you
     *  need to call {@link #close()} to do that,
     *  because we assume you'll still be populating
     *  the Info/Comment/Setup objects
     */
    public void writeVideoData(TheoraVideoData data) {
        writtenPackets.add(data);
    }
    /**
     * Buffers the given audio ready for writing
     *  out, to a given (pre-existing) audio stream. 
     * Data won't be written out yet, you
     *  need to call {@link #close()} to do that,
     *  because we assume you'll still be populating
     *  the Info/Comment/Setup objects
     */
    public void writeAudioData(OggStreamAudioData data, int sid) {
        // TODO Buffer this somehow
    }

    /**
     * In Reading mode, will close the underlying ogg
     *  file and free its resources.
     * In Writing mode, will write out the Info, Comment
     *  Tags objects, and then the video and audio data.
     * TODO Support Skeletons too
     */
    public void close() throws IOException {
        if(r != null) {
            r = null;
            ogg.close();
            ogg = null;
        }
        if(w != null) {
            // First, write the initial packet of each stream
            // Skeleton (if present) goes first, then video, then audio(s)
            if (skeleton != null) {
                w.bufferPacket(skeleton.getFishead().write(), true);
            }
            w.bufferPacket(info.write(), true);
            for (OggAudioStreamHeaders audio : soundtracks.values()) {
                // TODO How to let these be write-able?
                //w.bufferPacket(audio.getInfo().write(), true);
            }

            // Next, provide the rest of the skeleton information, to
            //  make it easy to work out what's what
            if (skeleton != null) {
                for (SkeletonFisbone bone : skeleton.getFisbones()) {
                    w.bufferPacket(bone.write(), true);
                }
                for (SkeletonKeyFramePacket frame : skeleton.getKeyFrames()) {
                    w.bufferPacket(frame.write(), true);
                }
            }

            // Next is the rest of the Theora headers
            w.bufferPacket(comments.write(), true);
            w.bufferPacket(setup.write(), true);

            // Finish the headers with the soundtrack stream remaining headers
            // TODO Soundtracks

            // TODO Write video, with some sort of granule
            // TODO Write audio

            w.close();
            w = null;
            ogg.close();
            ogg = null;
        }
    }

    /**
     * Returns the underlying Ogg File instance
     * @return
     */
    public OggFile getOggFile() {
        return ogg;
    }
}
