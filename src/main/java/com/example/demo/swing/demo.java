package com.example.demo.swing;

import lombok.Data;

import javax.swing.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

/**
 * @author mark
 * @describe SWING测试demo
 * @date 2022/7/29 16:22
 */
@Data
public class demo {
    private static JTextArea area;

    public static void main(String[] args) throws InterruptedException {
        // 创建 JFrame 实例
        JFrame frame = new JFrame("Login Example");
        // Setting the width and height of frame
        frame.setSize(350, 200);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        /* 创建面板，这个类似于 HTML 的 div 标签
         * 我们可以创建多个面板并在 JFrame 中指定位置
         * 面板中我们可以添加文本字段，按钮及其他组件。
         */
        JPanel panel = new JPanel();
        // 添加面板
        frame.add(panel);
        /*
         * 调用用户定义的方法并添加组件到面板
         */
        try {
            placeComponents(panel);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        // 设置界面可见
        frame.setVisible(true);

        Thread.sleep(1000);

        area.setText("测试");
        area.paintImmediately(area.getBounds());


    }

    private static void placeComponents(JPanel panel) throws InterruptedException {

        /* 布局部分我们这边不多做介绍
         * 这边设置布局为 null
         */
        panel.setLayout(null);

        // 创建 JLabel
        JLabel userLabel = new JLabel("User:");
        /* 这个方法定义了组件的位置。
         * setBounds(x, y, width, height)
         * x 和 y 指定左上角的新位置，由 width 和 height 指定新的大小。
         */
        userLabel.setBounds(10, 20, 80, 25);
        panel.add(userLabel);

        /*
         * 创建文本域用于用户输入
         */
        JTextField userCommand = new JTextField(20);
        userCommand.setBounds(100, 20, 165, 25);
        panel.add(userCommand);

        // 发送按钮
        JButton sendButton = new JButton("Send");
        sendButton.setBounds(10, 80, 80, 25);
        sendButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                String command = userCommand.getText();
                System.out.println("=======收到命令" + command);
            }
        });
        panel.add(sendButton);

        /*
         * 用于返显服务端回馈的内容
         */
        JTextArea jTextArea = new JTextArea();
        jTextArea.setBounds(100, 50, 605, 250);
        jTextArea.setText("helloshsshdsadhahdlasshdisahdisaadiasihdaibcssjbcufudufbcssg");
        panel.add(jTextArea);


    }
}
