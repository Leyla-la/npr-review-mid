package updatedfinalmulticast;
import javax.swing.*;
import java.awt.*;
import java.io.*;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicBoolean;

/*
 ClientGUI (Broadcast Mode)
 - GUI client cho chế độ broadcast/unicast
 - Trách nhiệm:
   * Kết nối tới server, gửi/nhận tin nhắn
   * Gửi/broadcast file (upload) tới server
   * Hiển thị log, tiến trình upload (progress bar) và thông báo
 - Ghi chú: thêm dialog tiến trình upload để người dùng thấy trạng thái gửi file rõ ràng
*/
public class ClientGUI extends JFrame {
    private static final String HOST = "localhost";
    private static final int PORT = 4075;

    // --- Socket và streams ---
    private Socket socket;
    private BufferedReader reader;
    private BufferedWriter writer;
    private DataInputStream dataIn;
    private DataOutputStream dataOut;

    // --- GUI components ---
    private JTextArea txtLog;
    private JTextField txtStudentId;
    private JTextField txtMessage;
    private JButton btnConnect;
    private JButton btnSend;
    private JButton btnBroadcast;
    private JButton btnSendFile;
    private JButton btnDisconnect;
    private JLabel lblStatus;
    private JProgressBar progressBar; // small progress bar in connection panel

    private boolean isConnected = false;
    private Thread messageListener;

    // --- Upload dialog (modeless) ---
    private JDialog uploadDialog;            // dialog hiển thị tiến trình upload
    private JProgressBar dialogProgressBar;  // lớn hơn, hiển thị %
    private JLabel dialogProgressLabel;      // mô tả chi tiết (Speed/ETA..)
    // Cancellation helpers for upload
    private volatile Thread uploadThread;
    private final AtomicBoolean uploadCancelled = new AtomicBoolean(false);

    public ClientGUI() {
        initializeGUI();
    }

    /*
     initializeGUI()
     - Xây dựng layout chính: connection panel, log panel, message panel
     - Thiết lập listener để disconnect khi đóng cửa sổ
    */
    private void initializeGUI() {
        setTitle("TCP Client - Broadcast Mode");
        setSize(750, 650);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLocationRelativeTo(null);

        JPanel mainPanel = new JPanel(new BorderLayout(10, 10));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        mainPanel.add(createConnectionPanel(), BorderLayout.NORTH);
        mainPanel.add(createLogPanel(), BorderLayout.CENTER);
        mainPanel.add(createMessagePanel(), BorderLayout.SOUTH);

        add(mainPanel);

        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                disconnect();
            }
        });
    }

    /*
     createConnectionPanel()
     - Panel trên cùng: Student ID, Connect/Disconnect, status và progress nhỏ
    */
    private JPanel createConnectionPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createTitledBorder("Connection"));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        gbc.gridx = 0; gbc.gridy = 0;
        panel.add(new JLabel("Student ID:"), gbc);

        gbc.gridx = 1; gbc.weightx = 1.0;
        txtStudentId = new JTextField("12345", 15);
        panel.add(txtStudentId, gbc);

        gbc.gridx = 2; gbc.weightx = 0;
        btnConnect = new JButton("Connect");
        btnConnect.addActionListener(e -> connect());
        panel.add(btnConnect, gbc);

        gbc.gridx = 3;
        btnDisconnect = new JButton("Disconnect");
        btnDisconnect.setEnabled(false);
        btnDisconnect.addActionListener(e -> disconnect());
        panel.add(btnDisconnect, gbc);

        gbc.gridx = 0; gbc.gridy = 1; gbc.gridwidth = 4;
        lblStatus = new JLabel("Status: Disconnected");
        lblStatus.setForeground(Color.RED);
        lblStatus.setFont(new Font("Arial", Font.BOLD, 12));
        panel.add(lblStatus, gbc);

        // Progress bar nhỏ (hiển thị khi upload)
        gbc.gridy = 2;
        progressBar = new JProgressBar();
        progressBar.setStringPainted(true);
        progressBar.setVisible(false);
        panel.add(progressBar, gbc);

        return panel;
    }

    /*
     createLogPanel()
     - Vùng log ở giữa: đổi font sang Times New Roman để phù hợp yêu cầu
    */
    private JPanel createLogPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createTitledBorder("Communication Log"));

        txtLog = new JTextArea();
        txtLog.setEditable(false);
        // Đổi font log sang Times New Roman cho dễ đọc
        txtLog.setFont(new Font("Times New Roman", Font.PLAIN, 12));
        JScrollPane scrollPane = new JScrollPane(txtLog);
        panel.add(scrollPane, BorderLayout.CENTER);

        return panel;
    }

    /*
     createMessagePanel()
     - Panel dưới cùng: input message, các nút gửi
    */
    private JPanel createMessagePanel() {
        JPanel panel = new JPanel(new BorderLayout(5, 5));
        panel.setBorder(BorderFactory.createTitledBorder("Send Message / File"));

        JPanel inputPanel = new JPanel(new BorderLayout(5, 5));
        inputPanel.add(new JLabel("Message:"), BorderLayout.WEST);
        txtMessage = new JTextField();
        txtMessage.setEnabled(false);
        txtMessage.addActionListener(e -> sendMessage());
        inputPanel.add(txtMessage, BorderLayout.CENTER);
        panel.add(inputPanel, BorderLayout.CENTER);

        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));

        btnSend = new JButton("Send (Unicast)");
        btnSend.setEnabled(false);
        btnSend.setToolTipText("Send to server only");
        btnSend.addActionListener(e -> sendMessage());
        btnPanel.add(btnSend);

        btnBroadcast = new JButton("Broadcast");
        btnBroadcast.setEnabled(false);
        btnBroadcast.setToolTipText("Send to all connected clients");
        btnBroadcast.addActionListener(e -> broadcastMessage());
        btnPanel.add(btnBroadcast);

        btnSendFile = new JButton("Send File (Broadcast)");
        btnSendFile.setEnabled(false);
        btnSendFile.addActionListener(e -> sendFile());
        btnPanel.add(btnSendFile);

        panel.add(btnPanel, BorderLayout.EAST);

        return panel;
    }

    /*
     connect()
     - Mở socket, tạo streams và gửi Student_ID
     - Bắt đầu message listener thread
    */
    private void connect() {
        String studentId = txtStudentId.getText().trim();
        if (studentId.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "Please enter Student ID!",
                    "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        try {
            log("Connecting to " + HOST + ":" + PORT + "...");

            socket = new Socket(HOST, PORT);
            reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));
            dataIn = new DataInputStream(socket.getInputStream());
            dataOut = new DataOutputStream(socket.getOutputStream());

            isConnected = true;
            updateConnectionStatus(true);
            log("✓ Connected successfully!\n");

            // Gửi Student_ID
            log("→ Sending Student_ID: " + studentId);
            writer.write(studentId);
            writer.newLine();
            writer.flush();

            String response = reader.readLine();
            if (response != null) {
                log("← Received (4×ID): " + response + "\n");
            }

            // Start message listener thread
            startMessageListener();

        } catch (IOException e) {
            log("✗ Connection failed: " + e.getMessage() + "\n");
            JOptionPane.showMessageDialog(this,
                    "Cannot connect to server!\n" + e.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
            updateConnectionStatus(false);
        }
    }

    /*
     startMessageListener()
     - Lắng nghe các message từ server trên một thread riêng
     - Xử lý broadcast message và broadcast file commands
    */
    private void startMessageListener() {
        messageListener = new Thread(() -> {
            try {
                String line;
                while (isConnected && (line = reader.readLine()) != null) {
                    if ("BROADCAST_MSG".equals(line)) {
                        String message = reader.readLine();
                        log("[BROADCAST] " + message);
                        SwingUtilities.invokeLater(() -> Toolkit.getDefaultToolkit().beep());

                    }
                    else if ("BROADCAST_FILE".equals(line)) {
                        receiveFile();
                    }
                }
            } catch (IOException e) {
                if (isConnected) {
                    log("✗ Connection lost: " + e.getMessage());
                    SwingUtilities.invokeLater(this::disconnect);
                }
            }
        });
        messageListener.start();
    }

    private void disconnect() {
        if (!isConnected) return;

        isConnected = false;

        try {
            log("\n→ Sending QUIT signal...");
            writer.write("QUIT");
            writer.newLine();
            writer.flush();

            Thread.sleep(100);

            if (reader != null) reader.close();
            if (writer != null) writer.close();
            if (dataIn != null) dataIn.close();
            if (dataOut != null) dataOut.close();
            if (socket != null && !socket.isClosed()) socket.close();

            if (messageListener != null) messageListener.interrupt();

            log("✓ Disconnected successfully\n");

        } catch (Exception e) {
            log("✗ Error during disconnect: " + e.getMessage() + "\n");
        } finally {
            updateConnectionStatus(false);
        }
    }

    private void sendMessage() {
        if (!isConnected) return;

        String message = txtMessage.getText().trim();
        if (message.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "Please enter a message!",
                    "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        try {
            log("→ Sending (Unicast): " + message);
            writer.write(message);
            writer.newLine();
            writer.flush();

            String response = reader.readLine();
            if (response != null) {
                try {
                    new java.math.BigInteger(message);
                    log("← Received ([" + message + "]^4): " + response + "\n");
                } catch (NumberFormatException e) {
                    log("← Received (echo): " + response + "\n");
                }
            }

            txtMessage.setText("");

        } catch (IOException e) {
            log("✗ Send error: " + e.getMessage() + "\n");
            disconnect();
        }
    }

    private void broadcastMessage() {
        if (!isConnected) return;

        String message = txtMessage.getText().trim();
        if (message.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "Please enter a message!",
                    "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        try {
            log("Broadcasting: " + message);
            writer.write("BROADCAST_MSG");
            writer.newLine();
            writer.write(message);
            writer.newLine();
            writer.flush();

            String response = reader.readLine();
            if ("BROADCAST_OK".equals(response)) {
                log("✓ Message broadcasted to all clients\n");
            }

            txtMessage.setText("");

        } catch (IOException e) {
            log("✗ Broadcast error: " + e.getMessage() + "\n");
            disconnect();
        }
    }

    /*
     ensureUploadDialog()
     - Tạo dialog modeless với progress bar lớn và nhãn mô tả
     - Dialog được sử dụng để hiển thị tiến trình upload song song với progressBar nhỏ
    */
    private void ensureUploadDialog() {
        if (uploadDialog != null) return;

        uploadDialog = new JDialog(this, "Uploading...", Dialog.ModalityType.MODELESS);
        uploadDialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
        uploadDialog.setSize(420, 140);
        uploadDialog.setResizable(false);

        JPanel content = new JPanel(new BorderLayout(10, 10));
        content.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        dialogProgressLabel = new JLabel("Starting upload...");
        dialogProgressLabel.setFont(new Font("Times New Roman", Font.PLAIN, 12));
        content.add(dialogProgressLabel, BorderLayout.NORTH);

        dialogProgressBar = new JProgressBar(0, 100);
        dialogProgressBar.setStringPainted(true);
        dialogProgressBar.setValue(0);
        content.add(dialogProgressBar, BorderLayout.CENTER);

        // South: Cancel button to request upload cancellation (sends CANCEL frame)
        JPanel south = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton btnCancel = new JButton("Cancel");
        btnCancel.setFont(new Font("Times New Roman", Font.PLAIN, 12));
        btnCancel.addActionListener(e -> {
            uploadCancelled.set(true);
            dialogProgressLabel.setText("Cancelling...");
            btnCancel.setEnabled(false);
        });
        south.add(btnCancel);
        content.add(south, BorderLayout.SOUTH);

        uploadDialog.getContentPane().add(content);
        uploadDialog.setLocationRelativeTo(this);
    }

    /*
     sendFile()
     - Gửi file theo luồng nền và cập nhật cả progressBar nhỏ + dialogProgressBar
     - Sử dụng framed protocol: for each chunk sendInt(len)+bytes; 0=end; -1=cancel
    */
    private void sendFile() {
        if (!isConnected) return;

        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setDialogTitle("Select file to broadcast");

        if (fileChooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            File file = fileChooser.getSelectedFile();

            // Disable buttons during upload
            setButtonsEnabled(false);
            progressBar.setVisible(true);
            progressBar.setValue(0);
            progressBar.setString("Preparing...");

            // Ensure dialog exists and show it (modeless)
            ensureUploadDialog();
            SwingUtilities.invokeLater(() -> {
                dialogProgressBar.setValue(0);
                dialogProgressLabel.setText("Preparing upload...");
                uploadDialog.setLocationRelativeTo(this);
                uploadDialog.setVisible(true);
            });

            // Upload in background thread
            uploadCancelled.set(false);
            uploadThread = new Thread(() -> {
                try {
                    log("\nBroadcasting file: " + file.getName() + " (" + file.length() + " bytes)");

                    writer.write("FILE");
                    writer.newLine();
                    writer.flush();

                    dataOut.writeUTF(file.getName());
                    dataOut.writeLong(file.length());
                    dataOut.flush();

                    // Upload with framed chunks: writeInt(len)+bytes; 0=end; -1=cancel
                    try (FileInputStream fis = new FileInputStream(file)) {
                        byte[] buffer = new byte[4096];
                        int bytesRead;
                        long totalSent = 0;
                        long fileSize = file.length();

                        while ((bytesRead = fis.read(buffer)) != -1) {
                            if (uploadCancelled.get()) {
                                try { dataOut.writeInt(-1); dataOut.flush(); } catch (IOException ignored) {}
                                log("✗ Upload cancelled by user (sent CANCEL frame)");
                                break;
                            }

                            dataOut.writeInt(bytesRead);
                            dataOut.write(buffer, 0, bytesRead);
                            dataOut.flush();

                            totalSent += bytesRead;

                            int progress = (int)((totalSent * 100) / fileSize);
                            SwingUtilities.invokeLater(() -> {
                                progressBar.setValue(progress);
                                progressBar.setString("Uploading: " + progress + "%");
                                if (dialogProgressBar != null) {
                                    dialogProgressBar.setValue(progress);
                                    dialogProgressBar.setString(progress + "%");
                                }
                                if (dialogProgressLabel != null) dialogProgressLabel.setText(
                                        String.format("Uploading: %d%% (%s / %s)",
                                                progress, formatFileSize(totalSent), formatFileSize(fileSize)));
                            });
                        }

                        if (!uploadCancelled.get()) {
                            try { dataOut.writeInt(0); dataOut.flush(); } catch (IOException ignored) {}
                        }
                    }

                    // Wait for server response (SUCCESS / CANCELLED / ERROR)
                    String serverResponse = null;
                    try {
                        serverResponse = dataIn.readUTF();
                    } catch (IOException ignored) {}

                    if (uploadCancelled.get()) {
                        log("✗ Upload cancelled locally");
                        if (serverResponse != null) log("<< Server response after cancel: " + serverResponse);
                        SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this,
                                "Upload cancelled.", "Cancelled", JOptionPane.INFORMATION_MESSAGE));

                    } else if ("SUCCESS".equals(serverResponse)) {
                        log("✓ File broadcasted successfully!\n");
                        SwingUtilities.invokeLater(() -> {
                            progressBar.setString("Complete!");
                            if (dialogProgressLabel != null) dialogProgressLabel.setText("Upload complete!");
                            JOptionPane.showMessageDialog(this,
                                    "File broadcasted to all clients!", "Success", JOptionPane.INFORMATION_MESSAGE);
                        });
                    } else {
                        log("✗ Broadcast failed: " + serverResponse + "\n");
                        SwingUtilities.invokeLater(() -> {
                            progressBar.setString("Failed!");
                            if (dialogProgressLabel != null) dialogProgressLabel.setText("Upload failed");
                            JOptionPane.showMessageDialog(this,
                                    "Broadcast failed: " + serverResponse, "Error", JOptionPane.ERROR_MESSAGE);
                        });
                    }

                } catch (IOException e) {
                    log("✗ File send error: " + e.getMessage() + "\n");
                    SwingUtilities.invokeLater(() -> {
                        progressBar.setString("Error!");
                        if (dialogProgressLabel != null) dialogProgressLabel.setText("Error during upload");
                        JOptionPane.showMessageDialog(this,
                                "Cannot send file!\n" + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
                    });
                } finally {
                    SwingUtilities.invokeLater(() -> {
                        uploadThread = null;
                        uploadCancelled.set(false);
                        setButtonsEnabled(true);
                        new Timer(1500, evt -> {
                            progressBar.setVisible(false);
                            progressBar.setValue(0);
                            if (uploadDialog != null && uploadDialog.isVisible()) uploadDialog.setVisible(false);
                            ((Timer)evt.getSource()).stop();
                        }).start();
                    });
                }
            });
            uploadThread.start();
        }
    }

    private void receiveFile() {
        try {
            String fileName = dataIn.readUTF();
            long fileSize = dataIn.readLong();

            log("\nReceiving broadcast file: " + fileName + " (" + fileSize + " bytes)");

            SwingUtilities.invokeLater(() -> {
                progressBar.setVisible(true);
                progressBar.setValue(0);
                progressBar.setString("Downloading: 0%");

                // Sync dialog if present
                if (dialogProgressBar != null) {
                    dialogProgressBar.setValue(0);
                    dialogProgressLabel.setText("Downloading...");
                    uploadDialog.setLocationRelativeTo(this);
                    uploadDialog.setVisible(true);
                }
            });

            File downloadDir = new File("client_downloads/");
            downloadDir.mkdirs();

            File saveFile = new File(downloadDir, fileName);

            try (FileOutputStream fos = new FileOutputStream(saveFile)) {
                byte[] buffer = new byte[4096];
                long totalReceived = 0;

                while (true) {
                    int frameLen = dataIn.readInt();
                    if (frameLen == -1) {
                        // sender cancelled
                        log("✗ Sender cancelled upload\n");
                        break;
                    }
                    if (frameLen == 0) {
                        // finished
                        break;
                    }

                    int remaining = frameLen;
                    while (remaining > 0) {
                        int toRead = Math.min(remaining, buffer.length);
                        int actuallyRead = dataIn.read(buffer, 0, toRead);
                        if (actuallyRead == -1) throw new EOFException("Unexpected EOF while receiving frame");
                        fos.write(buffer, 0, actuallyRead);
                        totalReceived += actuallyRead;
                        remaining -= actuallyRead;
                    }

                    int progress = (fileSize > 0) ? (int)((totalReceived * 100) / fileSize) : 0;
                    SwingUtilities.invokeLater(() -> {
                        progressBar.setValue(progress);
                        progressBar.setString("Downloading: " + progress + "%");
                        if (dialogProgressBar != null) {
                            dialogProgressBar.setValue(progress);
                            dialogProgressBar.setString(progress + "%");
                            dialogProgressLabel.setText(String.format("Downloading: %d%% (%s / %s)",
                                    progress, formatFileSize(totalReceived), formatFileSize(fileSize)));
                        }
                    });
                }
            }

            log("✓ File saved: " + new File("client_downloads/", fileName).getAbsolutePath() + "\n");

            SwingUtilities.invokeLater(() -> {
                progressBar.setString("Download complete!");
                if (dialogProgressLabel != null) dialogProgressLabel.setText("Download complete!");
                Toolkit.getDefaultToolkit().beep();

                new Timer(1500, evt -> {
                    progressBar.setVisible(false);
                    progressBar.setValue(0);
                    if (uploadDialog != null && uploadDialog.isVisible()) uploadDialog.setVisible(false);
                    ((Timer)evt.getSource()).stop();
                }).start();
            });

        } catch (IOException e) {
            log("✗ File receive error: " + e.getMessage() + "\n");
            SwingUtilities.invokeLater(() -> {
                progressBar.setString("Download failed!");
                progressBar.setVisible(false);
                if (dialogProgressLabel != null) dialogProgressLabel.setText("Download failed");
                if (uploadDialog != null && uploadDialog.isVisible()) uploadDialog.setVisible(false);
            });
        }
    }

    private void setButtonsEnabled(boolean enabled) {
        btnSend.setEnabled(enabled);
        btnBroadcast.setEnabled(enabled);
        btnSendFile.setEnabled(enabled);
    }

    private void updateConnectionStatus(boolean connected) {
        isConnected = connected;
        btnConnect.setEnabled(!connected);
        btnDisconnect.setEnabled(connected);
        txtStudentId.setEnabled(!connected);
        txtMessage.setEnabled(connected);
        setButtonsEnabled(connected);

        if (connected) {
            lblStatus.setText("Status: Connected to " + HOST + ":" + PORT);
            lblStatus.setForeground(new Color(0, 150, 0));
        } else {
            lblStatus.setText("Status: Disconnected");
            lblStatus.setForeground(Color.RED);
        }
    }

    private void log(String message) {
        SwingUtilities.invokeLater(() -> {
            txtLog.append(message + "\n");
            txtLog.setCaretPosition(txtLog.getDocument().getLength());
        });
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception e) {
                e.printStackTrace();
            }
            new ClientGUI().setVisible(true);
        });
    }

    // Định dạng kích thước file cho dễ đọc (ví dụ: 1.5 MB)
    private String formatFileSize(long size) {
        String[] units = {"B", "KB", "MB", "GB"};
        int i = 0;
        double doubleSize = size;

        while (i < units.length - 1 && size >= 1024) {
            size /= 1024;
            i++;
        }

        return String.format("%.1f %s", doubleSize, units[i]);
    }
}