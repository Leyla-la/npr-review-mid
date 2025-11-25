package mid2025ca01;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.net.Socket;

public class ClientGUI extends JFrame {
    private static final String HOST = "localhost";
    private static final int PORT = 4075;
    
    // Network components
    private Socket socket;
    private BufferedReader reader;
    private BufferedWriter writer;
    private DataInputStream dataIn;
    private DataOutputStream dataOut;
    
    // GUI components
    private JTextArea txtLog;
    private JTextField txtStudentId;
    private JTextField txtMessage;
    private JButton btnConnect;
    private JButton btnSend;
    private JButton btnSendFile;
    private JButton btnDisconnect;
    private JLabel lblStatus;
    
    private boolean isConnected = false;

    public ClientGUI() {
        initializeGUI();
    }

    private void initializeGUI() {
        setTitle("TCP Client - Mathematics & File Transfer");
        setSize(700, 550);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLocationRelativeTo(null);
        
        // Main panel với BorderLayout
        JPanel mainPanel = new JPanel(new BorderLayout(10, 10));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        
        // Top panel - Connection
        JPanel topPanel = createConnectionPanel();
        mainPanel.add(topPanel, BorderLayout.NORTH);
        
        // Center panel - Log
        JPanel centerPanel = createLogPanel();
        mainPanel.add(centerPanel, BorderLayout.CENTER);
        
        // Bottom panel - Message
        JPanel bottomPanel = createMessagePanel();
        mainPanel.add(bottomPanel, BorderLayout.SOUTH);
        
        add(mainPanel);
        
        // Window closing handler
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                disconnect();
            }
        });
    }

    private JPanel createConnectionPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createTitledBorder("Connection"));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        
        // Student ID
        gbc.gridx = 0; gbc.gridy = 0;
        panel.add(new JLabel("Student ID:"), gbc);
        
        gbc.gridx = 1; gbc.weightx = 1.0;
        txtStudentId = new JTextField("12345", 15);
        panel.add(txtStudentId, gbc);
        
        // Connect button
        gbc.gridx = 2; gbc.weightx = 0;
        btnConnect = new JButton("Connect");
        btnConnect.addActionListener(e -> connect());
        panel.add(btnConnect, gbc);
        
        // Disconnect button
        gbc.gridx = 3;
        btnDisconnect = new JButton("Disconnect");
        btnDisconnect.setEnabled(false);
        btnDisconnect.addActionListener(e -> disconnect());
        panel.add(btnDisconnect, gbc);
        
        // Status label
        gbc.gridx = 0; gbc.gridy = 1; gbc.gridwidth = 4;
        lblStatus = new JLabel("Status: Disconnected");
        lblStatus.setForeground(Color.RED);
        panel.add(lblStatus, gbc);
        
        return panel;
    }

    private JPanel createLogPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createTitledBorder("Communication Log"));
        
        txtLog = new JTextArea();
        txtLog.setEditable(false);
        txtLog.setFont(new Font("Consolas", Font.PLAIN, 12));
        JScrollPane scrollPane = new JScrollPane(txtLog);
        panel.add(scrollPane, BorderLayout.CENTER);
        
        return panel;
    }

    private JPanel createMessagePanel() {
        JPanel panel = new JPanel(new BorderLayout(5, 5));
        panel.setBorder(BorderFactory.createTitledBorder("Send Message / File"));
        
        // Message input
        JPanel inputPanel = new JPanel(new BorderLayout(5, 5));
        inputPanel.add(new JLabel("Message:"), BorderLayout.WEST);
        txtMessage = new JTextField();
        txtMessage.setEnabled(false);
        txtMessage.addActionListener(e -> sendMessage()); // Enter to send
        inputPanel.add(txtMessage, BorderLayout.CENTER);
        panel.add(inputPanel, BorderLayout.CENTER);
        
        // Buttons
        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        
        btnSend = new JButton("Send Message");
        btnSend.setEnabled(false);
        btnSend.addActionListener(e -> sendMessage());
        btnPanel.add(btnSend);
        
        btnSendFile = new JButton("Send File");
        btnSendFile.setEnabled(false);
        btnSendFile.addActionListener(e -> sendFile());
        btnPanel.add(btnSendFile);
        
        panel.add(btnPanel, BorderLayout.EAST);
        
        return panel;
    }

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
            
            // Gửi Student_ID đầu tiên
            log("→ Sending Student_ID: " + studentId);
            writer.write(studentId);
            writer.newLine();
            writer.flush();
            
            // Nhận phản hồi 4*ID
            String response = reader.readLine();
            if (response != null) {
                log("← Received (4×ID): " + response + "\n");
            }
            
        } catch (IOException e) {
            log("✗ Connection failed: " + e.getMessage() + "\n");
            JOptionPane.showMessageDialog(this, 
                "Cannot connect to server!\n" + e.getMessage(), 
                "Error", JOptionPane.ERROR_MESSAGE);
            updateConnectionStatus(false);
        }
    }

    private void disconnect() {
        if (!isConnected) return;
        
        try {
            log("\n→ Sending QUIT signal...");
            writer.write("QUIT");
            writer.newLine();
            writer.flush();
            
            Thread.sleep(100); // Đợi server xử lý
            
            if (reader != null) reader.close();
            if (writer != null) writer.close();
            if (dataIn != null) dataIn.close();
            if (dataOut != null) dataOut.close();
            if (socket != null && !socket.isClosed()) socket.close();
            
            log("✓ Disconnected successfully\n");
            
        } catch (Exception e) {
            log("✗ Error during disconnect: " + e.getMessage() + "\n");
        } finally {
            isConnected = false;
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
            log("→ Sending: " + message);
            writer.write(message);
            writer.newLine();
            writer.flush();
            
            String response = reader.readLine();
            if (response != null) {
                // Kiểm tra xem có phải số dương không để hiển thị đúng
                try {
                    new java.math.BigInteger(message);
                    log("← Received ([" + message + "]^4): " + response + "\n");
                } catch (NumberFormatException e) {
                    log("← Received (echo): " + response + "\n");
                }
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

    private void sendFile() {
        if (!isConnected) return;
        
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setDialogTitle("Select file to send");
        
        if (fileChooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            File file = fileChooser.getSelectedFile();
            
            try {
                log("\n→ Sending FILE command...");
                writer.write("FILE");
                writer.newLine();
                writer.flush();
                
                // Gửi tên file
                dataOut.writeUTF(file.getName());
                // Gửi kích thước file
                dataOut.writeLong(file.length());
                dataOut.flush();
                
                log("  ↑ Uploading: " + file.getName() + " (" + file.length() + " bytes)");
                
                // Gửi nội dung file
                try (FileInputStream fis = new FileInputStream(file)) {
                    byte[] buffer = new byte[4096];
                    int bytesRead;
                    long totalSent = 0;
                    
                    while ((bytesRead = fis.read(buffer)) != -1) {
                        dataOut.write(buffer, 0, bytesRead);
                        totalSent += bytesRead;
                    }
                    dataOut.flush();
                }
                
                // Nhận xác nhận từ server
                String serverResponse = dataIn.readUTF();
                
                if ("SUCCESS".equals(serverResponse)) {
                    log("  ✓ File uploaded successfully!\n");
                    JOptionPane.showMessageDialog(this, 
                        "File uploaded successfully!", 
                        "Success", JOptionPane.INFORMATION_MESSAGE);
                } else {
                    log("  ✗ Upload failed: " + serverResponse + "\n");
                    JOptionPane.showMessageDialog(this, 
                        "Upload failed: " + serverResponse, 
                        "Error", JOptionPane.ERROR_MESSAGE);
                }
                
            } catch (IOException e) {
                log("  ✗ File send error: " + e.getMessage() + "\n");
                JOptionPane.showMessageDialog(this, 
                    "Cannot send file!\n" + e.getMessage(), 
                    "Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void updateConnectionStatus(boolean connected) {
        isConnected = connected;
        btnConnect.setEnabled(!connected);
        btnDisconnect.setEnabled(connected);
        txtStudentId.setEnabled(!connected);
        txtMessage.setEnabled(connected);
        btnSend.setEnabled(connected);
        btnSendFile.setEnabled(connected);
        
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
}