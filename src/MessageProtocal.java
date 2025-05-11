// MessageTypes.java
import java.io.Serializable;
import java.util.List;

public class MessageProtocal {
    // Base message type
    public static class Message implements Serializable {
        public String txnId;
        
        public Message(String txnId) {
            this.txnId = txnId;
        }
    }
    
    // Prepare message (Server -> UserNode)
    public static class PrepareMessage extends Message {
        byte[] imgBytes;
        public List<String> requestedImages;
        public PrepareMessage(String txnId, byte[] imgBytes, List<String> requestedImages) {
            super(txnId);
            this.imgBytes = imgBytes;
            this.requestedImages = requestedImages;
        }
    }
    
    // Vote message (UserNode -> Server)
    public static class VoteMessage extends Message {
        public boolean vote;       
        public VoteMessage(String txnId, boolean vote) {
            super(txnId);
            this.vote = vote;
        }
    }
    
    // Commit message (Server -> UserNode)
    public static class CommitMessage extends Message {
        public CommitMessage(String txnId) {
            super(txnId);
        }
    }
    
    // Abort message (Server -> UserNode)
    public static class AbortMessage extends Message {
        public AbortMessage(String txnId) {
            super(txnId);
        }
    }
    
    // Acknowledgment message (UserNode -> Server)
    public static class AckMessage extends Message {
        public AckMessage(String txnId, boolean isCommit) {
            super(txnId);
        }
    }
}