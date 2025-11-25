package updatedmid2025unicast;

import javax.swing.*;
import java.awt.*;
import java.io.*;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/*
 ClientGUI.java
 - Đây là lớp GUI cho client hoạt động ở chế độ unicast.
 - Trách nhiệm chính:
   * Hiển thị giao diện kết nối, log, gửi tin nhắn và gửi file
   * Quản lý kết nối TCP tới server (socket + streams)
   * Thực hiện upload file theo framed protocol (gửi tên file, kích thước, rồi các frame: int len + bytes). Hỗ trợ CANCEL (-1) và EOF (0).
   * Hiển thị progress upload trong một dialog modeless và cho phép hủy upload mà không đóng kết nối.
 - Lưu ý: giao diện được khởi tạo với font Times New Roman toàn cục.
 */
public class ClientGUI extends JFrame {
    // Logger để ghi lỗi/exception thay vì in stack trace
    private static final Logger LOGGER = Logger.getLogger(ClientGUI.class.getName());
    // --- Cấu hình server ---
    private static final String HOST = "localhost"; // host server mặc định
    private static final int PORT = 4075;             // port server mặc định

    // --- Streams / socket ---
    private Socket socket;                // socket kết nối tới server
    private BufferedReader reader;        // để đọc dòng text từ server
    private BufferedWriter writer;        // để gửi dòng text đến server
    private DataInputStream dataIn;       // để đọc dữ liệu nhị phân (ví dụ, phản hồi upload)
    private DataOutputStream dataOut;     // để gửi dữ liệu nhị phân (frame file)

    // --- GUI components ---
    private JTextArea txtLog;             // vùng log giao tiếp
    private JTextField txtStudentId;      // ô nhập Student ID
    private JTextField txtMessage;        // ô nhập message
    private JButton btnConnect;           // nút connect
    private JButton btnSend;              // nút gửi message
    private JButton btnSendFile;          // nút gửi file
    private JButton btnDisconnect;        // nút disconnect
    private JLabel lblStatus;             // hiển thị trạng thái kết nối
    private JProgressBar progressBar;     // progress bar nhỏ ở panel connection
    private JLabel lblProgressText;       // hiển thị text trạng thái upload

    private boolean isConnected = false;  // trạng thái kết nối

    // --- Upload dialog & cancellation ---
    private JDialog uploadDialog;         // dialog modeless hiển thị tiến trình upload
    private JProgressBar dialogProgressBar; // progress bar lớn trong dialog
    private JLabel dialogProgressLabel;   // nhãn trạng thái trong dialog

    // upload thread và cờ hủy (thread-safe bằng AtomicBoolean)
    private volatile Thread uploadThread; // tham chiếu tới thread upload hiện tại
    private final AtomicBoolean uploadCancelled = new AtomicBoolean(false);

    /*
     Constructor
     - Gọi setGlobalFont để đặt Times New Roman cho toàn UI trước khi tạo components
     - Gọi initializeGUI() để build layout
    */
    public ClientGUI() {
        // Apply global UI font before creating components
        setGlobalFont(new Font("Times New Roman", Font.PLAIN, 12));
        initializeGUI();
    }

    /*
     setGlobalFont(font)
     - Đặt font mặc định (UIManager defaults) cho toàn bộ components Swing
     - Giúp đồng bộ font trong toàn ứng dụng
    */
    private void setGlobalFont(Font font) {
        java.util.Enumeration<?> keys = UIManager.getDefaults().keys();
        while (keys.hasMoreElements()) {
            Object key = keys.nextElement();
            Object value = UIManager.get(key);
            if (value instanceof javax.swing.plaf.FontUIResource) {
                UIManager.put(key, new javax.swing.plaf.FontUIResource(font.getFamily(), font.getStyle(), font.getSize()));
            }
        }
    }

    /*
     initializeGUI()
     - Xây dựng cửa sổ chính: connection panel (trên), log (giữa), send panel (dưới)
     - Thiết lập listener để gọi disconnect() khi đóng cửa sổ
    */
    private void initializeGUI() {
        setTitle("TCP Client - Unicast Mode");
        setSize(750, 650);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLocationRelativeTo(null);

        JPanel mainPanel = new JPanel(new BorderLayout(10, 10));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        mainPanel.add(createConnectionPanel(), BorderLayout.NORTH);
        mainPanel.add(createLogPanel(), BorderLayout.CENTER);
        mainPanel.add(createMessagePanel(), BorderLayout.SOUTH);

        add(mainPanel);

        // Khi đóng cửa sổ, cố gắng ngắt kết nối an toàn
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                disconnect();
            }
        });
    }

    /*
     createConnectionPanel()
     - Panel phía trên chứa Student ID, các nút Connect/Disconnect, trạng thái và progress nhỏ
    */
    private JPanel createConnectionPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createTitledBorder("Connection"));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        // Row 0: Student ID + Buttons
        gbc.gridx = 0; gbc.gridy = 0;
        panel.add(new JLabel("Student ID:"), gbc);

        gbc.gridx = 1; gbc.weightx = 1.0;
        txtStudentId = new JTextField("12345", 15);
        panel.add(txtStudentId, gbc);

        gbc.gridx = 2; gbc.weightx = 0;
        btnConnect = new JButton(">> Connect");
        btnConnect.setFont(new Font("Times New Roman", Font.BOLD, 12));
        btnConnect.addActionListener(e -> connect()); // bắt sự kiện connect
        panel.add(btnConnect, gbc);

        gbc.gridx = 3;
        btnDisconnect = new JButton("<< Disconnect");
        btnDisconnect.setFont(new Font("Times New Roman", Font.BOLD, 12));
        btnDisconnect.setEnabled(false);
        btnDisconnect.addActionListener(e -> disconnect());
        panel.add(btnDisconnect, gbc);

        // Row 1: Status
        gbc.gridx = 0; gbc.gridy = 1; gbc.gridwidth = 4;
        lblStatus = new JLabel("Status: Disconnected");
        lblStatus.setForeground(Color.RED);
        lblStatus.setFont(new Font("Times New Roman", Font.BOLD, 12));
        panel.add(lblStatus, gbc);

        // Row 2: Progress bar (nhỏ)
        gbc.gridy = 2;
        progressBar = new JProgressBar();
        progressBar.setStringPainted(true);
        progressBar.setPreferredSize(new Dimension(0, 25));
        progressBar.setVisible(false);
        panel.add(progressBar, gbc);

        // Row 3: Progress text
        gbc.gridy = 3;
        lblProgressText = new JLabel(" ");
        lblProgressText.setFont(new Font("Times New Roman", Font.PLAIN, 12));
        lblProgressText.setForeground(new Color(100, 100, 100));
        panel.add(lblProgressText, gbc);

        return panel;
    }

    /*
     createLogPanel()
     - Vùng log trung tâm: hiển thị tất cả các message giao tiếp và thông báo
    */
    private JPanel createLogPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createTitledBorder("Communication Log"));

        txtLog = new JTextArea();
        txtLog.setEditable(false);
        // Use Times New Roman for logs as requested
        txtLog.setFont(new Font("Times New Roman", Font.PLAIN, 12));
        txtLog.setBackground(new Color(250, 250, 250));
        JScrollPane scrollPane = new JScrollPane(txtLog);
        panel.add(scrollPane, BorderLayout.CENTER);

        return panel;
    }

    /*
     createMessagePanel()
     - Panel dưới chứa ô nhập message, nút gửi message và nút gửi file
    */
    private JPanel createMessagePanel() {
        JPanel panel = new JPanel(new BorderLayout(5, 5));
        panel.setBorder(BorderFactory.createTitledBorder("Send Message / File"));

        JPanel inputPanel = new JPanel(new BorderLayout(5, 5));
        inputPanel.add(new JLabel("Message:"), BorderLayout.WEST);
        txtMessage = new JTextField();
        txtMessage.setEnabled(false);
        txtMessage.setFont(new Font("Times New Roman", Font.PLAIN, 12));
        txtMessage.addActionListener(e -> sendMessage());
        inputPanel.add(txtMessage, BorderLayout.CENTER);
        panel.add(inputPanel, BorderLayout.CENTER);

        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 5));

        btnSend = new JButton(">> Send Message");
        btnSend.setEnabled(false);
        btnSend.setFont(new Font("Times New Roman", Font.BOLD, 12));
        btnSend.addActionListener(e -> sendMessage());
        btnPanel.add(btnSend);

        btnSendFile = new JButton(">> Send File");
        btnSendFile.setEnabled(false);
        btnSendFile.setFont(new Font("Times New Roman", Font.BOLD, 12));
        btnSendFile.addActionListener(e -> sendFile());
        btnPanel.add(btnSendFile);

        panel.add(btnPanel, BorderLayout.EAST);

        return panel;
    }

    /*
     connect()
     - Kiểm tra Student ID, mở socket và các stream
     - Gửi Student_ID lên server và chờ phản hồi (4×ID)
     - Cập nhật GUI khi kết nối thành công / thất bại
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
            log("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            log("Connecting to " + HOST + ":" + PORT + "...");

            socket = new Socket(HOST, PORT);
            reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));
            dataIn = new DataInputStream(socket.getInputStream());
            dataOut = new DataOutputStream(socket.getOutputStream());

            isConnected = true;
            updateConnectionStatus(true);
            log("✓ Connected successfully!");
            log("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");

            // Gửi Student_ID
            log(">> Sending Student_ID: " + studentId);
            writer.write(studentId);
            writer.newLine();
            writer.flush();

            // Nhận phản hồi 4*ID
            String response = reader.readLine();
            if (response != null) {
                log("<< Received (4×" + studentId + "): " + response);
                log("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
            }

        } catch (IOException e) {
            log("✗ Connection failed: " + e.getMessage());
            log("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
            JOptionPane.showMessageDialog(this,
                    "Cannot connect to server!\n" + e.getMessage(),
                    "Connection Error", JOptionPane.ERROR_MESSAGE);
            updateConnectionStatus(false);
        }
    }

    /*
     disconnect()
     - Gửi QUIT tới server, đóng các stream và socket
     - Cập nhật GUI
    */
    private void disconnect() {
        if (!isConnected) return;

        try {
            log("\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            log(">> Sending QUIT signal...");
            writer.write("QUIT");
            writer.newLine();
            writer.flush();

            Thread.sleep(100);

            if (reader != null) reader.close();
            if (writer != null) writer.close();
            if (dataIn != null) dataIn.close();
            if (dataOut != null) dataOut.close();
            if (socket != null && !socket.isClosed()) socket.close();

            log("✓ Disconnected successfully");
            log("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");

        } catch (Exception e) {
            log("✗ Error during disconnect: " + e.getMessage());
            log("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
        } finally {
            isConnected = false;
            updateConnectionStatus(false);
        }
    }

    /*
     sendMessage()
     - Gửi message dạng dòng text và chờ phản hồi server
     - Nếu message là số (numeric), server sẽ trả (num^4) else echo
    */
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
            log("\n>> Sending: " + message);
            writer.write(message);
            writer.newLine();
            writer.flush();

            String response = reader.readLine();
            if (response != null) {
                try {
                    new java.math.BigInteger(message);
                    log("<< Received ([" + message + "]^4): " + response);
                } catch (NumberFormatException e) {
                    log("<< Received (echo): " + response);
                }
                log("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
            } else {
                log("✗ Server disconnected\n");
                disconnect();
            }

            txtMessage.setText("");

        } catch (IOException e) {
            log("✗ Send error: " + e.getMessage() + "\n");
            disconnect();
        }
    }

    /*
     sendFile()
     - Chọn file bằng JFileChooser
     - Gửi lệnh "FILE" (dòng text), gửi fileName (UTF) và fileSize (long)
     - Gửi dữ liệu theo framed protocol:
         for each chunk: writeInt(len); write(bytes)
         0 => EOF (kết thúc thành công)
         -1 => CANCEL (client yêu cầu server huỷ)
     - Dialog modeless cập nhật tiến trình; có nút Cancel để đặt cờ uploadCancelled
     - Client sẽ đợi phản hồi UTF từ server (SUCCESS / ERROR / CANCELLED)
    */
    private void sendFile() {
        if (!isConnected) return;

        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setDialogTitle("Select file to send");

        if (fileChooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            File file = fileChooser.getSelectedFile();

            // Disable buttons during upload
            setButtonsEnabled(false);
            showProgress(true);

            // Upload in background thread
            uploadCancelled.set(false);
            uploadThread = new Thread(() -> {
                try {
                    log("\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
                    log(">> Uploading file: " + file.getName());
                    log("   Size: " + formatFileSize(file.length()));

                    writer.write("FILE");
                    writer.newLine();
                    writer.flush();

                    dataOut.writeUTF(file.getName());
                    dataOut.writeLong(file.length());
                    dataOut.flush();

                    updateProgressText("Preparing file...");

                    boolean cancelled = false;

                    // Upload with framed chunks: writeInt(len) + bytes; send 0 for EOF, -1 for CANCEL
                    try (FileInputStream fis = new FileInputStream(file)) {
                        byte[] buffer = new byte[4096];
                        int bytesRead;
                        long totalSent = 0;
                        long fileSize = file.length();
                        long startTime = System.currentTimeMillis();
                        long lastUpdateTime = startTime;

                        while ((bytesRead = fis.read(buffer)) != -1) {
                            if (uploadCancelled.get()) {
                                // send cancel frame
                                try {
                                    dataOut.writeInt(-1);
                                    dataOut.flush();
                                } catch (IOException ignored) {}
                                cancelled = true;
                                log("✗ Upload cancelled by user (sent CANCEL frame)");
                                break;
                            }

                            // send chunk
                            dataOut.writeInt(bytesRead);
                            dataOut.write(buffer, 0, bytesRead);
                            dataOut.flush();

                            totalSent += bytesRead;

                            long currentTime = System.currentTimeMillis();
                            if (currentTime - lastUpdateTime > 100 || totalSent == fileSize) {
                                int progress = (int)((totalSent * 100) / fileSize);
                                long elapsed = currentTime - startTime;
                                double speed = totalSent / (elapsed / 1000.0); // bytes/sec
                                long remaining = (long)((fileSize - totalSent) / Math.max(1.0, speed));

                                updateProgress(progress);
                                updateProgressText(String.format(
                                        "Uploading: %s / %s | Speed: %s/s | ETA: %ds",
                                        formatFileSize(totalSent),
                                        formatFileSize(fileSize),
                                        formatFileSize((long)speed),
                                        remaining
                                ));

                                lastUpdateTime = currentTime;
                            }
                        }

                        if (!cancelled) {
                            // indicate EOF
                            try {
                                dataOut.writeInt(0);
                                dataOut.flush();
                            } catch (IOException ignored) {}
                        }
                    }

                    if (cancelled) {
                        updateProgressText("Upload cancelled");
                        // wait for server ack/error (optional) but don't disconnect
                        try {
                            String serverResponse = dataIn.readUTF();
                            log("<< Server response after cancel: " + serverResponse);
                        } catch (IOException ignored) {}

                        SwingUtilities.invokeLater(() -> {
                            JOptionPane.showMessageDialog(this,
                                    "Upload cancelled.",
                                    "Cancelled", JOptionPane.INFORMATION_MESSAGE);
                        });

                    } else {
                        updateProgressText("Waiting for server confirmation...");

                        String serverResponse = dataIn.readUTF();

                        if ("SUCCESS".equals(serverResponse)) {
                            log("✓ File uploaded successfully!");
                            updateProgress(100);
                            updateProgressText("Upload complete!");

                            SwingUtilities.invokeLater(() -> {
                                JOptionPane.showMessageDialog(this,
                                        "File uploaded successfully!\n" + file.getName(),
                                        "Success", JOptionPane.INFORMATION_MESSAGE);
                            });
                        } else {
                            log("✗ Upload failed: " + serverResponse);
                            updateProgressText("Upload failed!");

                            SwingUtilities.invokeLater(() -> {
                                JOptionPane.showMessageDialog(this,
                                        "Upload failed: " + serverResponse,
                                        "Error", JOptionPane.ERROR_MESSAGE);
                            });
                        }
                    }

                    log("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");

                } catch (IOException e) {
                    log("✗ File send error: " + e.getMessage());
                    log("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
                    updateProgressText("Error occurred!");

                    SwingUtilities.invokeLater(() -> {
                        JOptionPane.showMessageDialog(this,
                                "Cannot send file!\n" + e.getMessage(),
                                "Error", JOptionPane.ERROR_MESSAGE);
                    });
                } finally {
                    SwingUtilities.invokeLater(() -> {
                        uploadThread = null;
                        uploadCancelled.set(false);
                        setButtonsEnabled(true);
                        // Hide progress after 2 seconds
                        new Timer(2000, evt -> {
                            showProgress(false);
                            updateProgress(0);
                            updateProgressText(" ");
                            ((Timer)evt.getSource()).stop();
                        }).start();
                    });
                }
            });
            uploadThread.start();
        }
    }

    /*
     updateProgress(value)
     - Cập nhật progress bar nhỏ và dialog progress (nếu tồn tại)
    */
    private void updateProgress(int value) {
        SwingUtilities.invokeLater(() -> {
            progressBar.setValue(value);
            progressBar.setString(value + "%");
            // also update dialog progress if present
            if (dialogProgressBar != null) {
                dialogProgressBar.setValue(value);
                dialogProgressBar.setString(value + "%");
            }
        });
    }

    /*
     updateProgressText(text)
     - Cập nhật text mô tả trạng thái upload ở panel và dialog
    */
    private void updateProgressText(String text) {
        SwingUtilities.invokeLater(() -> {
            lblProgressText.setText(text);
            if (dialogProgressLabel != null) dialogProgressLabel.setText(text);
        });
    }

    /*
     showProgress(show)
     - Hiển thị/ẩn dialog upload modeless và progress bar nhỏ
     - Modeless: cho phép người dùng vẫn tương tác với cửa sổ chính (vì upload chạy nền)
    */
    private void showProgress(boolean show) {
        // Keep the small status progress bar in the connection panel in sync
        SwingUtilities.invokeLater(() -> progressBar.setVisible(show));

        if (show) {
            // show modeless upload dialog (non-blocking)
            SwingUtilities.invokeLater(() -> {
                ensureUploadDialog();
                if (dialogProgressBar != null) {
                    dialogProgressBar.setValue(progressBar.getValue());
                    dialogProgressBar.setString(progressBar.getString());
                }
                if (dialogProgressLabel != null) dialogProgressLabel.setText(lblProgressText.getText());
                uploadDialog.setLocationRelativeTo(this);
                uploadDialog.setVisible(true);
            });
        } else {
            SwingUtilities.invokeLater(() -> {
                if (uploadDialog != null && uploadDialog.isVisible()) {
                    uploadDialog.setVisible(false);
                }
                progressBar.setVisible(false);
            });
        }
    }

    /*
     ensureUploadDialog()
     - Tạo dialog modeless nếu chưa có
     - Dialog chứa nhãn trạng thái, progress bar lớn và nút Cancel
     - Nút Cancel đặt uploadCancelled = true và disable chính nó để tránh spam
    */
    private void ensureUploadDialog() {
        if (uploadDialog != null) return;

        uploadDialog = new JDialog(this, "Uploading...", Dialog.ModalityType.MODELESS);
        uploadDialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
        uploadDialog.setSize(480, 150);
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

        JPanel south = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton btnCancel = new JButton("Cancel");
        btnCancel.setFont(new Font("Times New Roman", Font.PLAIN, 12));
        btnCancel.addActionListener(e -> {
            // mark cancellation; upload thread will notice and send cancel frame
            uploadCancelled.set(true);
            // update label and disable cancel to prevent spam
            dialogProgressLabel.setText("Cancelling...");
            btnCancel.setEnabled(false);
        });
        south.add(btnCancel);

        content.add(south, BorderLayout.SOUTH);

        uploadDialog.getContentPane().add(content);
        uploadDialog.setLocationRelativeTo(this);
    }

    // formatFileSize: helper để format kích thước file cho log/UI
    private String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.2f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.2f MB", bytes / (1024.0 * 1024.0));
        return String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }

    private void setButtonsEnabled(boolean enabled) {
        btnSend.setEnabled(enabled);
        btnSendFile.setEnabled(enabled);
    }

    /*
     updateConnectionStatus(connected)
     - Cập nhật trạng thái GUI theo trạng thái kết nối
    */
    private void updateConnectionStatus(boolean connected) {
        isConnected = connected;
        btnConnect.setEnabled(!connected);
        btnDisconnect.setEnabled(connected);
        txtStudentId.setEnabled(!connected);
        txtMessage.setEnabled(connected);
        setButtonsEnabled(connected);

        if (connected) {
            lblStatus.setText("Status: ✓ Connected to " + HOST + ":" + PORT);
            lblStatus.setForeground(new Color(0, 150, 0));
        } else {
            lblStatus.setText("Status: ✗ Disconnected");
            lblStatus.setForeground(Color.RED);
        }
    }

    /*
     log(message)
     - Ghi message lên vùng txtLog (sử dụng invokeLater để an toàn với EDT)
    */
    private void log(String message) {
        SwingUtilities.invokeLater(() -> {
            txtLog.append(message + "\n");
            txtLog.setCaretPosition(txtLog.getDocument().getLength());
        });
    }

    // main: chạy client GUI
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Failed to set system look and feel", e);
            }
            new ClientGUI().setVisible(true);
        });
    }
}
