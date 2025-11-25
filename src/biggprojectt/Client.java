package biggprojectt;

// Client.java - FINAL 2025 SUPER CLIENT (GUI ĐẸP + FULL 15 CASE)
// Chỉ thay 3 dòng ở đầu là chạy

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.io.*;
import java.net.*;
import java.util.HashMap;

/*
 Client
 - GUI client cho bài FINAL: hỗ trợ nhiều tính năng (broadcast, private, rooms, file transfer, bank, poll,...)
 - Trách nhiệm chính:
   * Hiển thị giao diện (tabbed rooms, online list, bank, poll)
   * Kết nối tới server, gửi các command theo protocol đề bài
   * Nhận và xử lý các message control từ server (ID^4, ROOM, PRIVATE, FILE_INCOMING,...)
   * Nhận file từ server (SAVELOG / FILE_INCOMING) bằng việc đọc bytes trực tiếp từ socket input stream
 - Các chú thích block-level được đặt trong file để bạn dễ hiểu từng phần
 */
public class Client extends JFrame {
    private static final String HOST = "localhost";
    private static final int PORT = 1234;                    // THAY = 4 số cuối MSSV
    private static final String STUDENT_ID = "20230001";     // THAY = MSSV của bạn

    // --- Socket & streams ---
    private Socket socket;
    private DataOutputStream dataOut;
    private DataInputStream dataIn;
    // Upload cancellation helpers
    private volatile Thread uploadThread;
    private final java.util.concurrent.atomic.AtomicBoolean uploadCancelled = new java.util.concurrent.atomic.AtomicBoolean(false);

    // GUI Components
    private JTextArea mainChat;
    private JTextField inputField;
    private JList<String> onlineList;
    private DefaultListModel<String> onlineModel;
    private JTabbedPane roomTabs;
    private HashMap<String, JTextArea> roomAreas = new HashMap<>();
    private JLabel statusLabel, idPowerLabel, balanceLabel;

    // Bank history area reference
    private JTextArea bankHistoryArea;
    // Poll area components
    private JLabel pollQuestionLabel;
    private JPanel pollOptionsPanel;

    public Client() {
        setupGUI();
        connect();
    }

    /*
     setupGUI()
     - Xây dựng giao diện chính: top (ID^4 + balance), center (tabbed rooms), right (online + file button), bottom (input)
     - Thêm tab "Bank" và "Poll" theo yêu cầu, cùng nút Save Log
    */
    private void setupGUI() {
        setTitle("FINAL CLIENT - " + STUDENT_ID);
        setSize(900, 650);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setLayout(new BorderLayout());

        // Top panel: ID^4 + Balance
        JPanel top = new JPanel(new FlowLayout());
        idPowerLabel = new JLabel("ID^4: waiting...");
        balanceLabel = new JLabel("Số dư: 0");
        top.add(idPowerLabel);
        top.add(new JLabel("   |   "));
        top.add(balanceLabel);
        add(top, BorderLayout.NORTH);

        // Center: Tabbed chat rooms
        roomTabs = new JTabbedPane();
        mainChat = createChatArea();
        roomAreas.put("Main", mainChat);
        roomTabs.addTab("Main", new JScrollPane(mainChat));

        // --- Bank tab ---
        bankHistoryArea = createChatArea();
        bankHistoryArea.setBackground(new Color(10, 20, 30));
        JPanel bankPanel = new JPanel(new BorderLayout());
        bankPanel.add(new JScrollPane(bankHistoryArea), BorderLayout.CENTER);
        JPanel bankSouth = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton btnDeposit = new JButton("Deposit");
        btnDeposit.addActionListener(e -> {
            String amt = JOptionPane.showInputDialog(this, "Số tiền nạp:");
            if (amt != null && !amt.trim().isEmpty()) {
                try {
                    dataOut.writeUTF("DEPOSIT:" + amt.trim());
                    dataOut.flush();
                } catch (IOException ex) {
                    appendMain("Error sending deposit: " + ex.getMessage());
                }
            }
        });
        JButton btnWithdraw = new JButton("Withdraw");
        btnWithdraw.addActionListener(e -> {
            String amt = JOptionPane.showInputDialog(this, "Số tiền rút:");
            if (amt != null && !amt.trim().isEmpty()) {
                try {
                    dataOut.writeUTF("WITHDRAW:" + amt.trim());
                    dataOut.flush();
                } catch (IOException ex) {
                    appendMain("Error sending withdraw: " + ex.getMessage());
                }
            }
        });
        bankSouth.add(btnDeposit); bankSouth.add(btnWithdraw);
        bankPanel.add(bankSouth, BorderLayout.SOUTH);
        roomTabs.addTab("Bank", bankPanel);

        // --- Poll tab ---
        JPanel pollPanel = new JPanel(new BorderLayout());
        pollQuestionLabel = new JLabel("No active poll");
        pollOptionsPanel = new JPanel(new GridLayout(0,1));
        pollPanel.add(pollQuestionLabel, BorderLayout.NORTH);
        pollPanel.add(new JScrollPane(pollOptionsPanel), BorderLayout.CENTER);
        roomTabs.addTab("Poll", pollPanel);

        add(roomTabs, BorderLayout.CENTER);

        // Right: Online list + buttons
        JPanel right = new JPanel(new BorderLayout());
        right.setPreferredSize(new Dimension(200, 0));
        right.setBorder(BorderFactory.createTitledBorder("Online"));
        onlineModel = new DefaultListModel<>();
        onlineList = new JList<>(onlineModel);
        onlineList.addMouseListener(new java.awt.event.MouseAdapter() {
            public void mouseClicked(java.awt.event.MouseEvent evt) {
                if (evt.getClickCount() == 2) {
                    String selected = onlineList.getSelectedValue();
                    if (selected != null && !selected.equals(STUDENT_ID)) {
                        inputField.setText("PRIV:" + selected + ": ");
                        inputField.requestFocus();
                    }
                }
            }
        });
        right.add(new JScrollPane(onlineList), BorderLayout.CENTER);

        JPanel rightSouth = new JPanel(new GridLayout(0,1));
        JButton fileBtn = new JButton("Send File");
        fileBtn.addActionListener(e -> sendFile());
        JButton saveLogBtn = new JButton("Save Log");
        saveLogBtn.addActionListener(e -> {
            try {
                dataOut.writeUTF("SAVELOG");
                dataOut.flush();
            } catch (IOException ex) {
                appendMain("Error requesting save log: " + ex.getMessage());
            }
        });
        rightSouth.add(fileBtn);
        rightSouth.add(saveLogBtn);
        right.add(rightSouth, BorderLayout.SOUTH);

        add(right, BorderLayout.EAST);

        // Bottom: Input
        JPanel bottom = new JPanel(new BorderLayout());
        inputField = new JTextField();
        inputField.addActionListener(e -> sendMessage());
        inputField.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { if (dataOut != null) try { dataOut.writeUTF("TYPING:"); } catch (IOException ignored) {} }
            public void removeUpdate(DocumentEvent e) {}
            public void changedUpdate(DocumentEvent e) {}
        });
        bottom.add(inputField, BorderLayout.CENTER);

        JButton sendBtn = new JButton("Send");
        sendBtn.addActionListener(e -> sendMessage());
        bottom.add(sendBtn, BorderLayout.EAST);

        statusLabel = new JLabel("Status: Connecting...");
        bottom.add(statusLabel, BorderLayout.SOUTH);
        add(bottom, BorderLayout.SOUTH);

        setVisible(true);
    }

    /*
     createChatArea(): helper tạo JTextArea mặc định cho chat
    */
    private JTextArea createChatArea() {
        JTextArea area = new JTextArea();
        area.setEditable(false);
        area.setBackground(new Color(40, 44, 52));
        area.setForeground(Color.WHITE);
        area.setFont(new Font("Consolas", Font.PLAIN, 14));
        return area;
    }

    /*
     connect(): kết nối tới server và bắt đầu thread nhận dữ liệu
    */
    private void connect() {
        try {
            socket = new Socket(HOST, PORT);
            dataOut = new DataOutputStream(socket.getOutputStream());
            dataIn = new DataInputStream(socket.getInputStream());

            // Send initial ID using writeUTF (control channel)
            dataOut.writeUTF(STUDENT_ID);
            statusLabel.setText("Connected");

            new Thread(this::receiveLoop).start();
        } catch (Exception e) {
            statusLabel.setText("Lỗi kết nối!");
        }
    }

    /*
     sendMessage(): xây dựng message theo prefix và gửi
    */
    private void sendMessage() {
        String text = inputField.getText().trim();
        if (text.isEmpty()) return;

        if (text.equalsIgnoreCase("exit")) {
            try { dataOut.writeUTF("exit"); } catch (IOException ignored) {}
            System.exit(0);
        }

        if (text.startsWith("FILE:") || text.startsWith("PRIV:") || text.startsWith("ROOM:") ||
                text.startsWith("DEPOSIT:") || text.startsWith("WITHDRAW:") || text.startsWith("VOTE:") ||
                text.equals("LIST") || text.equals("WHOAMI") || text.equals("SAVELOG")) {
            try { dataOut.writeUTF(text); } catch (IOException ignored) {}
        } else {
            try { dataOut.writeUTF("MSG:" + text); } catch (IOException ignored) {}
        }
        inputField.setText("");
    }

    /*
     sendFile(): client gửi file tới server
     - Giao thức client->server: out.println("FILE:" + absolutePath)
     - Server trả READY, client gửi file metadata và raw bytes
    */
    private void sendFile() {
        JFileChooser fc = new JFileChooser();
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            File f = fc.getSelectedFile();
            // Prepare upload dialog with Cancel button
            ensureUploadDialog();
            uploadCancelled.set(false);
            SwingUtilities.invokeLater(() -> {
                uploadDialog.setTitle("Uploading: " + f.getName());
                dialogProgressBar.setValue(0);
                dialogProgressLabel.setText("Preparing upload...");
                if (!uploadDialog.isVisible()) uploadDialog.setVisible(true);
            });

            // Upload in background using framed protocol: writeUTF(FILE:...), then writeUTF(name), writeLong(size), then frames: int len + bytes; 0=end; -1=cancel
            uploadThread = new Thread(() -> {
                try {
                    dataOut.writeUTF("FILE:" + f.getAbsolutePath());
                    dataOut.flush();

                    String resp = dataIn.readUTF();
                    if (!"READY".equals(resp)) {
                        appendMain("Server not ready for file: " + resp);
                        return;
                    }

                    dataOut.writeUTF(f.getName());
                    dataOut.writeLong(f.length());
                    dataOut.flush();

                    try (FileInputStream fis = new FileInputStream(f)) {
                        byte[] buf = new byte[4096];
                        int read;
                        long totalSent = 0;
                        long fileSize = f.length();
                        while ((read = fis.read(buf)) != -1) {
                            if (uploadCancelled.get()) {
                                // send CANCEL frame and stop
                                try { dataOut.writeInt(-1); dataOut.flush(); } catch (IOException ignored) {}
                                appendMain("Upload cancelled by user (sent CANCEL)");
                                break;
                            }
                            dataOut.writeInt(read);
                            dataOut.write(buf, 0, read);
                            dataOut.flush();

                            totalSent += read;
                            final int prog = (int)((totalSent * 100) / Math.max(1, fileSize));
                            SwingUtilities.invokeLater(() -> {
                                dialogProgressBar.setValue(prog);
                                dialogProgressLabel.setText("Uploading: " + prog + "%");
                            });
                        }
                        if (!uploadCancelled.get()) {
                            try { dataOut.writeInt(0); dataOut.flush(); } catch (IOException ignored) {}
                        }
                    }

                    // wait for server ack (FILE_OK or FILE_STATUS|CANCELLED etc.)
                    String serverResp = dataIn.readUTF();
                    appendMain("Server response: " + serverResp);
                    SwingUtilities.invokeLater(() -> {
                        dialogProgressLabel.setText(serverResp);
                        new Timer(1200, ev -> { if (uploadDialog.isVisible()) uploadDialog.setVisible(false); ((Timer)ev.getSource()).stop(); }).start();
                    });

                } catch (IOException ex) {
                    appendMain("Upload error: " + ex.getMessage());
                    SwingUtilities.invokeLater(() -> {
                        dialogProgressLabel.setText("Error: " + ex.getMessage());
                        if (uploadDialog.isVisible()) uploadDialog.setVisible(false);
                    });
                } finally {
                    uploadThread = null;
                    uploadCancelled.set(false);
                }
            }, "upload-thread");
            uploadThread.start();
        }
    }

    /*
     receiveLoop(): vòng lắng nghe server gửi các dòng control/text
     - Khi gặp FILE_INCOMING|name|size -> gọi receiveFileFromServer để đọc raw bytes
    */
    private void receiveLoop() {
        try {
            String msg;
            while (true) {
                // Read control message as UTF string (server uses writeUTF for control)
                msg = dataIn.readUTF();
                // If server announces incoming file with metadata, handle streaming
                if (msg.startsWith("FILE_INCOMING|")) {
                    // format: FILE_INCOMING|name|size
                    String[] p = msg.split("\\|", 3);
                    if (p.length == 3) {
                        String name = p[1];
                        long size = Long.parseLong(p[2]);
                        receiveFileFromServer(name, size);
                        continue;
                    }
                }
                processMessage(msg);
            }
        } catch (Exception e) {
            appendMain("Mất kết nối server!");
        }
    }

    /*
     receiveFileFromServer(name, size)
     - Đọc trực tiếp 'size' bytes từ socket.getInputStream() và lưu vào thư mục client_downloads
     - Thông báo khi hoàn tất
    */
    private void receiveFileFromServer(String name, long size) {
        try {
            File downloadDir = new File("client_downloads");
            if (!downloadDir.exists()) {
                boolean ok = downloadDir.mkdirs();
                if (!ok) appendMain("Warning: cannot create download dir");
            }
            File save = new File(downloadDir, name);

            try (FileOutputStream fos = new FileOutputStream(save)) {
                // Read frames: server enqueues frames to client's sender which write int(len)+bytes; 0=end; -1=cancel
                long totalReceived = 0;
                while (true) {
                    int frameLen = dataIn.readInt();
                    if (frameLen == -1) { appendMain("Sender cancelled upload"); break; }
                    if (frameLen == 0) break;
                    int remaining = frameLen;
                    byte[] buffer = new byte[8192];
                    while (remaining > 0) {
                        int toRead = Math.min(remaining, buffer.length);
                        int r = dataIn.read(buffer, 0, toRead);
                        if (r == -1) throw new EOFException("Unexpected EOF");
                        fos.write(buffer, 0, r);
                        remaining -= r;
                        totalReceived += r;
                        final int prog = (int)((totalReceived*100)/Math.max(1,size));
                        SwingUtilities.invokeLater(() -> showDownloadProgress(name, prog));
                    }
                }
                fos.flush();
            }
            appendMain("Received file from server: " + name + " (" + size + " bytes)");
            SwingUtilities.invokeLater(() -> hideDownloadProgress());
        } catch (Exception ex) {
            appendMain("Error receiving file: " + ex.getMessage());
            SwingUtilities.invokeLater(() -> hideDownloadProgress());
        }
    }

    // Download progress UI
    private JDialog downloadDialog;
    private JProgressBar downloadProgressBar;
    private void showDownloadProgress(String name, int percent) {
        if (downloadDialog == null) {
            downloadDialog = new JDialog(this, "Downloading...", Dialog.ModalityType.MODELESS);
            downloadProgressBar = new JProgressBar(0,100);
            downloadProgressBar.setStringPainted(true);
            downloadDialog.add(new JLabel("Downloading: " + name), BorderLayout.NORTH);
            downloadDialog.add(downloadProgressBar, BorderLayout.CENTER);
            downloadDialog.setSize(350,100);
            downloadDialog.setLocationRelativeTo(this);
        }
        if (!downloadDialog.isVisible()) downloadDialog.setVisible(true);
        downloadProgressBar.setValue(percent);
    }

    private void hideDownloadProgress() {
        if (downloadDialog != null && downloadDialog.isVisible()) downloadDialog.setVisible(false);
    }

    // --- Upload dialog (shared) ---
    private JDialog uploadDialog;
    private JProgressBar dialogProgressBar;
    private JLabel dialogProgressLabel;
    private void ensureUploadDialog() {
        if (uploadDialog != null) return;
        uploadDialog = new JDialog(this, "Uploading...", Dialog.ModalityType.MODELESS);
        uploadDialog.setSize(360,120);
        uploadDialog.setResizable(false);
        JPanel p = new JPanel(new BorderLayout(8,8));
        dialogProgressLabel = new JLabel("Preparing...");
        dialogProgressLabel.setFont(new Font("Times New Roman", Font.PLAIN, 12));
        dialogProgressBar = new JProgressBar(0,100);
        dialogProgressBar.setStringPainted(true);
        p.setBorder(BorderFactory.createEmptyBorder(8,8,8,8));
        p.add(dialogProgressLabel, BorderLayout.NORTH);
        p.add(dialogProgressBar, BorderLayout.CENTER);
        JButton btnCancel = new JButton("Cancel");
        btnCancel.addActionListener(e -> {
            btnCancel.setEnabled(false);
            uploadCancelled.set(true);
            dialogProgressLabel.setText("Cancelling...");
            // upload thread will send -1 frame when it sees uploadCancelled
        });
        JPanel south = new JPanel(new FlowLayout(FlowLayout.RIGHT)); south.add(btnCancel);
        p.add(south, BorderLayout.SOUTH);
        uploadDialog.getContentPane().add(p);
        uploadDialog.setLocationRelativeTo(this);
    }

    /*
     processMessage(): xử lý dòng text control từ server, cập nhật UI
    */
    private void processMessage(String msg) {
        if (msg.startsWith("ID^4|")) {
            idPowerLabel.setText("ID^4: " + msg.substring(5));
        } else if (msg.startsWith("VOTE_RESULT|")) {
            // format: VOTE_RESULT|pollId|opt1:count,opt2:count,
            String[] p = msg.split("\\|",3);
            if (p.length==3) {
                String pollId = p[1];
                String res = p[2];
                pollQuestionLabel.setText("Results ("+pollId+")");
                pollOptionsPanel.removeAll();
                for (String kv : res.split(",")) if (!kv.isEmpty()) {
                    String[] kvp = kv.split(":");
                    JLabel l = new JLabel(kvp[0] + " -> " + kvp[1]);
                    pollOptionsPanel.add(l);
                }
                pollOptionsPanel.revalidate(); pollOptionsPanel.repaint();
            }
        } else if (msg.startsWith("ROOM|")) {
            String[] p = msg.split("\\|", 3);
            appendToRoom(p[1], p[2]);
        } else if (msg.startsWith("PRIVATE|")) {
            String[] p = msg.split("\\|", 3);
            appendMain(Color.MAGENTA, "[Riêng từ " + p[1] + "]: " + p[2]);
        } else if (msg.startsWith("BANK_BALANCE|")) {
            balanceLabel.setText("Số dư: " + msg.substring(13));
            // Optionally log balance in bank tab
            bankHistoryArea.append("Balance updated: " + msg.substring(13) + "\n");
        } else if (msg.startsWith("ONLINE_LIST|")) {
            updateOnlineList(msg.substring(12));
        } else if (msg.contains("đang gõ...")) {
            appendMain(Color.YELLOW, "→ " + msg);
        } else if (msg.startsWith("VOTE|")) {
            // simple: show poll text
            String[] parts = msg.split("\\|",3);
            if (parts.length>=3) {
                pollQuestionLabel.setText(parts[1]);
                pollOptionsPanel.removeAll();
                String[] opts = parts[2].split(",");
                for (String o: opts) {
                    JButton b = new JButton(o);
                    b.addActionListener(e -> {
                        try {
                            dataOut.writeUTF("VOTE:vote:"+pollQuestionLabel.getText()+":"+o);
                            dataOut.flush();
                        } catch (IOException ex) {
                            appendMain("Error sending vote: " + ex.getMessage());
                        }
                    });
                    pollOptionsPanel.add(b);
                }
                pollOptionsPanel.revalidate(); pollOptionsPanel.repaint();
            }
        } else {
            appendMain(msg);
        }
    }

    private void appendMain(String text) {
        appendMain(Color.CYAN, text);
    }

    private void appendMain(Color color, String text) {
        JTextArea area = roomAreas.get("Main");
        area.setForeground(color);
        area.append(text + "\n");
        area.setCaretPosition(area.getDocument().getLength());
    }

    private void appendToRoom(String room, String text) {
        JTextArea area = roomAreas.get(room);
        if (area == null) {
            area = createChatArea();
            roomAreas.put(room, area);
            roomTabs.addTab(room, new JScrollPane(area));
        }
        area.append(text + "\n");
        area.setCaretPosition(area.getDocument().getLength());
    }

    private void updateOnlineList(String csv) {
        onlineModel.clear();
        if (!csv.isEmpty()) {
            for (String id : csv.split(",")) {
                if (!id.isEmpty()) onlineModel.addElement(id);
            }
        }
    }

    public static void main(String[] args) {
        new Client();
    }
}
