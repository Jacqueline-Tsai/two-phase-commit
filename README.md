# Distributed Collage Commit with 2‑Phase Commit (2PC)

## Overview
This project implements a miniature **image‑collage version‑control system** that runs across a server and up to four user nodes.  
A central **Server** coordinates commits of composite images; each **UserNode** votes on whether to accept or abort a collage and then removes its local sources upon commit.  
Consensus is obtained with a **two‑phase commit (2PC)** protocol carried over `ProjectLib` message primitives.

---

## Structure
```yaml
.
├── README.md
├── docs/…
│   ├── design.pdf
│   └── lib # javadoc
├── lib/… # pre‑built Project4 / ProjectLib .class files
├── src/ # your implementation
│   ├── CoordinatorTransaction.java # 2PC state machine (server side)
│   ├── MessageProtocal.java # Serializable message types
│   ├── Server.java # Coordinator (CommitServing)
│   ├── UserNode.java # Participant (MessageHandling)
│   └── Makefile # simple javac build
└── test/… # sample images + scripts
```


*(other sub‑trees omitted for brevity)*

---

## Requirements
* **Java 8** (or newer) JDK
* UNIX‑like shell (for the provided `make` + test scripts)

---

## Build
```bash
cd src
make                # or:  javac -cp ../lib *.java
# Output .class files are written to src/ by default.
```


### How It Works
| Layer                      | Responsibility                                                                                                                                                                                                                                           |
| -------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **CoordinatorTransaction** | Tracks per‑commit state (`PREPARING → COMMITTING/ABORTING → COMMITTED/ABORTED`), votes, and pending ACKs.                                                                                                                                                |
| **Server**                 | *Coordinator*: broadcasts **Prepare**, collects **Vote**, writes collage on unanimous *YES*, then **Commit**; otherwise **Abort**. Persists state to `server_log.dat` for crash recovery.                                                                |
| **UserNode**               | *Participant*: on **Prepare**, calls `ProjectLib.askUser()` to display the collage, locks requested images, and votes YES/NO. Upon **Commit**, deletes sources and ACKs; on **Abort**, releases locks. Persists its own state (`usernode_<id>_log.dat`). |
| **Messaging**              | `ProjectLib.Message` is the transport; concrete messages live in `MessageProtocal.*`.                                                                                                                                                                    |

### Timeouts
- Server aborts if not all votes arrive within 3 s.
- Re‑sends Commit/Abort every 1 s until all ACKs received.

### Crash Recovery
- Both Server and UserNodes replay their logs on startup.
- Any in‑flight transaction in PREPARING state is forced to ABORT.
