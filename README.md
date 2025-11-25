Project: NPR Review - FINAL SUPER SYSTEM

Overview
--------
This workspace contains an enhanced TCP server and GUI client for the FINAL exam scenario (network programming). The server is multithreaded and supports 15 command cases including chat rooms, file transfer (streamed with cancel), a bank mini-system, voting/polling, private messages, and more. The client is a Swing GUI with tabs (Main, Bank, Poll), online list, file send, and file receive with a progress dialog.

Files changed/added
-------------------
- src/biggprojectt/Server.java  - main server with GUI log, per-client queues, file streaming, polls, bank, etc.
- src/biggprojectt/Client.java  - Swing GUI client, supports upload/download, polls, bank, "Save Log" feature.

Quick build & run (Windows PowerShell)
-------------------------------------
1) Compile
```powershell
javac -d out -sourcepath src src\biggprojectt\Server.java src\biggprojectt\Client.java
```
2) Run server (terminal A)
```powershell
java -cp out biggprojectt.Server
```
3) Run client (terminal B) — open multiple clients to simulate multiple users
```powershell
java -cp out biggprojectt.Client
```

Notes
-----
- Replace the constants at the top of the Java files for your Student ID and port (4 last digits of MSSV) before running.
- The server shows a simple GUI log window and writes to chat_log.txt / bank_log.txt in the working directory.

Testing features (short guides)
-------------------------------
1) Connect clients
- Start server, then open two or more clients. Each client sends its Student ID automatically on connect.
- Server replies with "ID^4|<value>" which the client shows near the top.

2) Chat (MSG:)
- Type a message and press Send. The client sends `MSG:<text>` and the server broadcasts to all users in the same room.

3) Private (PRIV:)
- Double-click an entry in the online list to prefill `PRIV:<target>:` and send a private message.

4) Rooms (ROOM:create:<name>, ROOM:join:<name>)
- Create and join rooms using the ROOM: prefix. Broadcasts in a room are only seen by members.

5) File upload & cancel (streamed)
- On client, click Send File and select a file. The client sends `FILE:<absPath>` and waits for server `READY`.
- The client then sends filename and size and streams raw bytes. The server stores the file and broadcasts to other clients by streaming frames.
- Server uses per-client queues and sender threads: the uploader is not blocked by slow receivers.
- Receivers get a download progress dialog and the saved file is in `client_downloads/`.

6) Save Log (SAVELOG)
- Click Save Log on client: client sends `SAVELOG` to server, server streams `chat_log.txt` back.

7) Bank (DEPOSIT:/WITHDRAW:)
- Deposit/Withdraw buttons in the Bank tab send `DEPOSIT:<amount>` or `WITHDRAW:<amount>`.
- Server maintains balances in `bank_log.txt` and replies with `BANK_BALANCE|<value>` shown on client.

8) Polls / Voting
- Server command `VOTE:create:Question?A:B:C` creates a poll with an ID (server broadcasts `VOTE|pollId|Question|A,B,C`).
- Clients show poll UI and can click an option to send `VOTE:vote:pollId:Option`.
- Server tallies votes and broadcasts `VOTE_RESULT|pollId|A:count,B:count,...` which updates client UI in real time.

9) Admin & KICK
- The first client that connects is the admin. Admin can send `KICK:<studentId>` to disconnect a user.

10) Edge cases
- Large file streaming is handled using fixed-size chunks and per-client queues. If a client's queue is full, the server logs and drops that client to protect uploader throughput.
- Filename sanitization and other security hardening should be added as needed.

Recommended next improvements
-----------------------------
- Add authentication and secure the control channel (use TLS).
- Improve backpressure: persist queued frames or use resumable downloads for very large files.
- Add unit/integration tests for upload/cancel and vote workflows.

If you'd like I will:
- Clean remaining IDE warnings, make fields final where appropriate.
- Add small automated test scripts that upload and cancel, exercise polling, and capture screenshots.
- Prepare a short demo script and sample screenshots.

Contact me which follow-up you prefer and I'll implement it and run the tests. 


