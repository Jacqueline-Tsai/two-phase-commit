import java.io.*;
import java.util.*;

/**
 * UserNode class that implements the message handling for a user node in a distributed system.
 * This class handles the 2PC protocol messages and manages the state of transactions and image locks.
 */
public class UserNode implements ProjectLib.MessageHandling {
    // Unique identifier for this UserNode
    public final String myId;

    // ProjectLib instance for sending and receiving messages
    private static ProjectLib PL;
    
    // Track active transactions (txnId -> list of images)
    private Map<String, List<String>> activeTransactions = new HashMap<>();
    
    // Track locked images (images -> txnId that locked it)
    private Map<String, String> lockedImages = new HashMap<>();
    
    // Logger instance
    private static NodeLogger logger;
    
    /**
     * Nested class to handle UserNode state logging and recovery.
     * This class is responsible for saving and recovering
     * the state of the UserNode to and from disk.
     * The state is saved in a file named "usernode_<id>_log.dat".
     */
    private class NodeLogger {
        private final String logFileName;
        
        /**
         * Constructor for NodeLogger
         */
        public NodeLogger() {
            this.logFileName = "usernode_" + myId + "_log.dat";
        }
        
        /**
         * Saves the UserNode state to disk
         */
        public void saveState() {
            try {
                FileOutputStream fos = new FileOutputStream(logFileName);
                ObjectOutputStream oos = new ObjectOutputStream(fos);
                
                // Write both maps to the log file
                oos.writeObject(activeTransactions);
                oos.writeObject(lockedImages);
                oos.close();
                fos.close();
                // Ensure data is persisted
                PL.fsync();
            } catch (Exception e) {
                System.err.println(myId + ": Error saving state: " + e.getMessage());
                e.printStackTrace();
            }
        }
        
        /**
         * Recovers the UserNode state from disk
         */
        @SuppressWarnings("unchecked")
        public void recoverState() {
            File logFile = new File(logFileName);
            if (!logFile.exists()) {
                return;
            }
            
            try {
                FileInputStream fis = new FileInputStream(logFileName);
                ObjectInputStream ois = new ObjectInputStream(fis);
                
                // Read both maps from the log file
                activeTransactions = (Map<String, List<String>>) ois.readObject();
                lockedImages = (Map<String, String>) ois.readObject();
                ois.close();
                fis.close();
            } catch (Exception e) {
                System.err.println(myId + ": Error recovering state: " + e.getMessage());
                e.printStackTrace();
                // Reset to empty state in case of errors
                activeTransactions = new HashMap<>();
                lockedImages = new HashMap<>();
            }
        }
    }

    /**
     * Constructor for UserNode
     * @param id The ID of the UserNode
     */
    public UserNode(String id) {
        myId = id;
        logger = new NodeLogger();
    }

    /**
     * Handles incoming messages
     * @param msg The message to handle
     * @return true if the message was handled, false otherwise
     */
    public boolean deliverMessage(ProjectLib.Message msg) {
        try {
            // Try to deserialize as a MessageProtocal object
            ByteArrayInputStream bis = new ByteArrayInputStream(msg.body);
            ObjectInputStream ois = new ObjectInputStream(bis);
            Object msgObj = ois.readObject();
            ois.close();
            bis.close();
            
            // Handle different message types
            if (msgObj instanceof MessageProtocal.PrepareMessage) {
                handlePrepareMessage(msg.addr, (MessageProtocal.PrepareMessage) msgObj);
                return true;
            } else if (msgObj instanceof MessageProtocal.CommitMessage) {
                handleCommitMessage(msg.addr, (MessageProtocal.CommitMessage) msgObj);
                return true;
            } else if (msgObj instanceof MessageProtocal.AbortMessage) {
                handleAbortMessage(msg.addr, (MessageProtocal.AbortMessage) msgObj);
                return true;
            }
        } catch (Exception e) {
            System.err.println(myId + ": Error handling message: " + e.getMessage());
            e.printStackTrace();
        }
        
        // If we reach here, it's not a 2PC message we recognize
        return false;
    }
    
    /**
     * Handles the PREPARE message from the coordinator
     * @param sender The address of the sender
     * @param prepMsg The prepare message object
     */
    private void handlePrepareMessage(String sender, MessageProtocal.PrepareMessage prepMsg) {
        String txnId = prepMsg.txnId;
        List<String> requestedImages = prepMsg.requestedImages;
        
        // Check if we have all the requested images and they're not locked or committed
        boolean canProceed = true;
        for (String image : requestedImages) {
            File imageFile = new File(image);
            
            // Check if file exists
            if (!imageFile.exists()) {
                canProceed = false;
                break;
            }
            
            // Check if image is locked by another transaction
            if (lockedImages.containsKey(image) && !lockedImages.get(image).equals(txnId)) {
                canProceed = false;
                break;
            }
        }
        
        // If we can't proceed, vote NO immediately
        if (!canProceed) {
            sendVoteMessage(sender, txnId, false);
            return;
        }
        
        // Ask the user if they approve the collage
        try {
            byte[] collageData = prepMsg.imgBytes;
            String[] imageArray = requestedImages.toArray(new String[0]);
            boolean userApproves = PL.askUser(collageData, imageArray);
            if (!userApproves) {
                sendVoteMessage(sender, txnId, false);
                return;
            }
            for (String image : requestedImages) {
                lockedImages.put(image, txnId);
            }
            // Record this transaction
            activeTransactions.put(txnId, requestedImages);
            logger.saveState();
            sendVoteMessage(sender, txnId, userApproves);
        } catch (Exception e) {
            System.err.println(myId + ": Error asking user: " + e.getMessage());
            e.printStackTrace();
            sendVoteMessage(sender, txnId, false);
        }
    }

    /**
     * Sends a vote message to the coordinator
     * @param recipient The address of the coordinator
     * @param txnId The transaction ID
     * @param vote The vote (true for YES, false for NO)
     */
    private void sendVoteMessage(String recipient, String txnId, boolean vote) {
        try {
            if (!vote) {
                abort(txnId);
            }
            MessageProtocal.VoteMessage voteMsg = new MessageProtocal.VoteMessage(txnId, vote);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ObjectOutputStream oos = new ObjectOutputStream(bos);
            oos.writeObject(voteMsg);
            byte[] msgData = bos.toByteArray();
            oos.close();
            bos.close();
            
            ProjectLib.Message msg = new ProjectLib.Message(recipient, msgData);
            PL.sendMessage(msg);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    /**
     * Handles the COMMIT message from the coordinator
     * @param sender The address of the sender
     * @param commitMsg The commit message object
     */
    private void handleCommitMessage(String sender, MessageProtocal.CommitMessage commitMsg) {
        String txnId = commitMsg.txnId;
        
        List<String> imagesToRemove = activeTransactions.get(txnId);
        if (imagesToRemove == null) {
            sendAckMessage(sender, txnId);
            return;
        }
        for (String image : imagesToRemove) {
            try {
                File imageFile = new File(image);
                if (imageFile.exists()) {
                    imageFile.delete();
                }
                lockedImages.remove(image);
            } catch (Exception e) {
                System.err.println(myId + ": Error removing image " + image + ": " + e.getMessage());
            }
        }
        activeTransactions.remove(txnId);
        logger.saveState();
        sendAckMessage(sender, txnId);
    }
    
    /**
     * Handles the ABORT message from the coordinator
     * @param sender The address of the sender
     * @param abortMsg The abort message object
     */
    private void handleAbortMessage(String sender, MessageProtocal.AbortMessage abortMsg) {
        String txnId = abortMsg.txnId;
        abort(txnId);
        // Send acknowledgment
        sendAckMessage(sender, txnId);
    }
    
    /**
     * Sends an acknowledgment message to the sender
     * @param recipient The address of the sender
     * @param txnId The transaction ID to acknowledge
     */
    private void sendAckMessage(String recipient, String txnId) {
        try {
            MessageProtocal.AckMessage ackMsg = new MessageProtocal.AckMessage(txnId, true);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ObjectOutputStream oos = new ObjectOutputStream(bos);
            oos.writeObject(ackMsg);
            byte[] msgData = bos.toByteArray();
            oos.close();
            bos.close();
            logger.saveState();

            ProjectLib.Message msg = new ProjectLib.Message(recipient, msgData);
            PL.sendMessage(msg);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Aborts the transaction with the given txnId
     * @param txnId The transaction ID to abort
     */
    private void abort(String txnId) {
        List<String> lockedByThisTxn = activeTransactions.get(txnId);
        if (lockedByThisTxn == null) {
            return;
        }
        for (String image : lockedByThisTxn) {
            if (!lockedImages.containsKey(image) || !lockedImages.get(image).equals(txnId)) {
                continue;
            }
            lockedImages.remove(image);
        }
        activeTransactions.remove(txnId);
        logger.saveState();
    }
    
    /**
     * Main method to start the UserNode
     * Usage: java UserNode <port> <id>
     */
    public static void main(String args[]) throws Exception {
        if (args.length != 2) throw new Exception("Need 2 args: <port> <id>");
        UserNode UN = new UserNode(args[1]);
        PL = new ProjectLib(Integer.parseInt(args[0]), args[1], UN);
        logger.recoverState();
        
        // Main thread can now wait
        while (true) {
            Thread.sleep(10000);
        }
    }
}