import java.io.Serializable;
import java.util.*;

public class CoordinatorTransaction implements Serializable {
    public enum State {
        INIT, PREPARING, COMMITTING, ABORTING, COMMITTED, ABORTED
    }
    
    private State state;
    private String id;
    private String filename;
    private byte[] imageData;
    private Map<String, List<String>> participantImages;
    private Set<String> voteReceived;
    private Set<String> ackRemained;
    
    public CoordinatorTransaction(String id, String filename, byte[] imageData, Map<String, List<String>> participantImages) {
        this.state = State.INIT;
        this.id = id;
        this.filename = filename;
        this.imageData = imageData;
        this.participantImages = participantImages;
        this.voteReceived = new HashSet<>();
        this.ackRemained = new HashSet<>();
        for (String nodeId : getParticipants()) {
            ackRemained.add(nodeId);
        }
    }
    
    public void receiveVote(String nodeId, boolean vote) {
        if (!vote) {
            state = State.ABORTING;
            return;
        }
        voteReceived.add(nodeId);
        if (voteReceived.size() == participantImages.size()) {
            state = State.COMMITTING;
        }
    }
    
    public void receiveAck(String nodeId) {
        ackRemained.remove(nodeId);
        if (!ackRemained.isEmpty()) {
            return;
        }
        if (state == State.COMMITTING) {
            state = State.COMMITTED;
        }
        if (state == State.ABORTING) {
            state = State.ABORTED;
        }
    }
    

    public String toString() {
        return "CoordinatorTransaction{" +
                "state=" + state +
                ", id='" + id + '\'' +
                ", filename='" + filename + '\'' +
                ", imageData=" + Arrays.toString(imageData) +
                ", participantImages=" + participantImages +
                ", voteReceived=" + voteReceived +
                ", ackRemained=" + ackRemained +
                '}';
    }

    // Getters and Setters
    public State getState() {
        return state;
    }

    public String getId() {
        return id;
    }

    public String getFilename() {
        return filename;
    }

    public byte[] getImageData() {
        return imageData;
    }

    public Set<String> getParticipants() {
        return participantImages.keySet();
    }

    public List<String> getImagesForParticipant(String nodeId) {
        return participantImages.getOrDefault(nodeId, Collections.emptyList());
    }

    public int getNumVoteReceived() {
        return voteReceived.size();
    }

    public Set<String> getAckRemained() {
        if (ackRemained == null || (state != State.COMMITTING && state != State.ABORTING)) {
            return Collections.emptySet();
        }
        return ackRemained;
    }

    // Setters
    public void setState(State newState) {
        state = newState;
    }
}