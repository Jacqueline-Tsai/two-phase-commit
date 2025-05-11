import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ConcurrentHashMap;

/* Server implementation with two-phase commit */
public class Server implements ProjectLib.CommitServing {
    private static ProjectLib PL;
    private static ConcurrentHashMap<String, CoordinatorTransaction> transactions = new ConcurrentHashMap<String, CoordinatorTransaction>();
    private AtomicLong txnCounter = new AtomicLong(0);
    private static ServerLogger logger;
    
    /**
     * Nested class to handle Server state logging
     */
    private class ServerLogger {
        private final String logFileName = "server_log.dat";
        
        /**
         * Saves the Server state to disk
         */
        public void saveState() {
            try {
                FileOutputStream fos = new FileOutputStream(logFileName);
                ObjectOutputStream oos = new ObjectOutputStream(fos);
                
                // Write transactions map and transaction counter
                oos.writeObject(transactions);
                oos.writeLong(txnCounter.get());
                
                oos.close();
                fos.close();
                
                // Ensure data is persisted
                PL.fsync();
            } catch (Exception e) {
                System.err.println("Server: Error saving state: " + e.getMessage());
                e.printStackTrace();
            }
        }
        
        /**
         * Recovers the Server state from disk
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
                
                // Read transactions map and transaction counter
                transactions = (ConcurrentHashMap<String, CoordinatorTransaction>) ois.readObject();
                long counter = ois.readLong();
                txnCounter.set(counter);
                ois.close();
                fis.close();

                for (String txnId : transactions.keySet()) {
                    CoordinatorTransaction txn = transactions.get(txnId);
                    if (txn == null || txn.getState() != CoordinatorTransaction.State.PREPARING) {
                        continue;
                    }
                    synchronized(txn) {
                        txn.setState(CoordinatorTransaction.State.ABORTING);
                        saveState();
                    }
                }
            } catch (Exception e) {
                System.err.println("Server: Error recovering state: " + e.getMessage());
                e.printStackTrace();
                
                // Reset to empty state in case of errors
                transactions = new ConcurrentHashMap<String, CoordinatorTransaction>();
                txnCounter.set(0);
            }
        }
    }

    /*
     * Constructor
     * Initializes the server and its logger
     */
    public Server() {
        logger = new ServerLogger();
    }

    /**
     * Generates a unique transaction ID
     * @return Unique transaction ID as a String
     */
    private String generateTxnId() {
        return String.valueOf(txnCounter.getAndIncrement());
    }

    /**
     * Sends a prepare message to a participant
     * @param nodeId ID of the participant node
     * @param txnId Transaction ID
     * @param imgBytes Image data as byte array
     * @param requestedImages List of requested images
     */
    private void sendPrepareMsg(String nodeId, String txnId, byte[] imgBytes, List<String> requestedImages) {
        try {
            MessageProtocal.PrepareMessage prepMsg = new MessageProtocal.PrepareMessage(txnId, imgBytes, requestedImages);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ObjectOutputStream oos = new ObjectOutputStream(bos);
            oos.writeObject(prepMsg);
            byte[] msgData = bos.toByteArray();
            oos.close();
            bos.close();
            ProjectLib.Message msg = new ProjectLib.Message(nodeId, msgData);
            PL.sendMessage(msg);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    /**
     * Sends a commit message to a participant
     * @param nodeId ID of the participant node
     * @param txnId Transaction ID
     */
    private static void sendCommitMsg(String nodeId, String txnId) {
        try {
            MessageProtocal.CommitMessage commitMsg = new MessageProtocal.CommitMessage(txnId);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ObjectOutputStream oos = new ObjectOutputStream(bos);
            oos.writeObject(commitMsg);
            byte[] msgData = bos.toByteArray();
            oos.close();
            bos.close();
            ProjectLib.Message msg = new ProjectLib.Message(nodeId, msgData);
            PL.sendMessage(msg);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    /**
     * Sends an abort message to a participant
     * @param nodeId ID of the participant node
     * @param txnId Transaction ID
     */
    private static void sendAbortMsg(String nodeId, String txnId) {
        try {
            MessageProtocal.AbortMessage abortMsg = new MessageProtocal.AbortMessage(txnId);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ObjectOutputStream oos = new ObjectOutputStream(bos);
            oos.writeObject(abortMsg);
            byte[] msgData = bos.toByteArray();
            oos.close();
            bos.close();
            ProjectLib.Message msg = new ProjectLib.Message(nodeId, msgData);
            PL.sendMessage(msg);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Starts the commit process for a given file
     * @param filename Name of the file to commit
     * @param img Image data as byte array
     * @param sources List of source nodes
     */
    public void startCommit(String filename, byte[] img, String[] sources) {
        // Generate unique transaction ID
        String txnId = generateTxnId();

        Map<String, List<String>> participantImages = new HashMap<>();
        for (String source : sources) {
            String[] sourceList = source.split(":");
            if (sourceList.length != 2) {
                System.err.println("Weird Source " + source);
                continue;
            }
            if (!participantImages.containsKey(sourceList[0])) {
                participantImages.put(sourceList[0], new ArrayList<>());
            }
            participantImages.get(sourceList[0]).add(sourceList[1]);
        }

        // Create and store transaction
        CoordinatorTransaction txn = new CoordinatorTransaction(txnId, filename, img, participantImages);
        txn.setState(CoordinatorTransaction.State.PREPARING);
        transactions.put(txnId, txn);
        logger.saveState();
        
        // Send prepare messages to all participants
        for (String nodeId : txn.getParticipants()) {
            sendPrepareMsg(nodeId, txnId, img, participantImages.get(nodeId));
        }

        // Start a timer for this transaction
        startTransactionPreparingTimer(txnId);
    }
    
    /**
     * Starts a timer for the transaction preparation phase
     * @param txnId Transaction ID
     */
    private void startTransactionPreparingTimer(String txnId) {
        new Thread(() -> {
            try {
                // Wait for a reasonable timeout (3 seconds)
                Thread.sleep(3000);
                CoordinatorTransaction txn = transactions.get(txnId);
                synchronized (txn) {
                    if (txn == null || txn.getState() != CoordinatorTransaction.State.PREPARING) {
                        return;
                    }
                    // Timeout occurred while still in PREPARING state
                    txn.setState(CoordinatorTransaction.State.ABORTING);
                    logger.saveState();
                    for (String nodeId : txn.getParticipants()) {
                        sendAbortMsg(nodeId, txn.getId());
                    }
                }
            } catch (InterruptedException e) {
                System.err.println("Server: CoordinatorTransaction timer interrupted: " + e.getMessage());
            }
        }).start();
    }
    
    /**
     * Saves the transaction image to disk
     * @param txn Transaction to save
     */
    private void saveTransactionImage(CoordinatorTransaction txn) {
        // Write the image to disk
        try {
            FileOutputStream fos = new FileOutputStream(txn.getFilename());
            fos.write(txn.getImageData());
            fos.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
        
        // Sync to ensure the file is written
        PL.fsync();
    }
    
    /**
     * Handles incoming messages from participants
     * @param msg Incoming message
     */
    private void handleMessage(ProjectLib.Message msg) {
        try {
            // Deserialize the message
            ByteArrayInputStream bis = new ByteArrayInputStream(msg.body);
            ObjectInputStream ois = new ObjectInputStream(bis);
            Object msgObj = ois.readObject();
            ois.close();
            bis.close();
            
            // Handle different message types
            if (msgObj instanceof MessageProtocal.VoteMessage) {
                MessageProtocal.VoteMessage voteMsg = (MessageProtocal.VoteMessage) msgObj;
                handleVoteMessage(msg.addr, voteMsg);
            } else if (msgObj instanceof MessageProtocal.AckMessage) {
                MessageProtocal.AckMessage ackMsg = (MessageProtocal.AckMessage) msgObj;
                handleAckMessage(msg.addr, ackMsg);
            } else {
                throw new Exception("Unknown message type: " + msgObj.getClass());
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    /**
     * Handles vote messages from participants
     * @param nodeId ID of the participant node
     * @param voteMsg Vote message
     */
    private void handleVoteMessage(String nodeId, MessageProtocal.VoteMessage voteMsg) {
        String txnId = voteMsg.txnId;
        boolean vote = voteMsg.vote;
        
        CoordinatorTransaction txn = transactions.get(txnId);
        synchronized (txn) {
			if (txn == null || txn.getState() != CoordinatorTransaction.State.PREPARING) {
				return;
			}
			txn.receiveVote(nodeId, vote);
			if (txn.getState() == CoordinatorTransaction.State.COMMITTING) {
				saveTransactionImage(txn);
			}
            logger.saveState();
		}
    }
    
    /**
     * Handles acknowledgment messages from participants
     * @param nodeId ID of the participant node
     * @param ackMsg Acknowledgment message
     */
    private void handleAckMessage(String nodeId, MessageProtocal.AckMessage ackMsg) {
        String txnId = ackMsg.txnId;        
        CoordinatorTransaction txn = transactions.get(txnId);
        if (txn == null) {
            return;
        }
        synchronized (txn) {
            txn.receiveAck(nodeId);
            logger.saveState();
        }
    }

    /**
     * Periodically checks for completed actions and sends commit/abort messages
     */
    private static void checkingActionsComplete() {
        new Thread(() -> {
            while (true) {
                // Wait for a reasonable timeout (3 seconds)
                try {
                    Thread.sleep(1000);
                    for (String txnId : transactions.keySet()) {
                        CoordinatorTransaction txn = transactions.get(txnId);
                        if (txn == null) continue;
                        synchronized (txn) {
                            if (txn == null || (txn.getState() != CoordinatorTransaction.State.COMMITTING && txn.getState() != CoordinatorTransaction.State.ABORTING)) {
                                continue;
                            }
                            for (String nodeId : txn.getAckRemained()) {
                                if (txn.getState() == CoordinatorTransaction.State.COMMITTING) {
                                    sendCommitMsg(nodeId, txn.getId());
                                } else if (txn.getState() == CoordinatorTransaction.State.ABORTING) {
                                    sendAbortMsg(nodeId, txn.getId());
                                }
                            }
                        }
                    }
                } catch (InterruptedException e) {
                    System.err.println("Server: Checking actions complete interrupted: " + e.getMessage());
                }
            }
        }).start();
    }
    
    public static void main(String args[]) throws Exception {
        if (args.length != 1) throw new Exception("Need 1 arg: <port>");
        Server srv = new Server();
        PL = new ProjectLib(Integer.parseInt(args[0]), srv);
        logger.recoverState();
        
        // sent out all preparing msg


        checkingActionsComplete();
        // main loop
        while (true) {
            ProjectLib.Message msg = PL.getMessage();
            srv.handleMessage(msg);
        }
    }
}