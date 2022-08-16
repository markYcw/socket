package com.example.demo.swing;

import com.example.demo.utils.ContextUtils;
import lombok.extern.slf4j.Slf4j;

import javax.swing.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

/**
 * @describe 客户端消息下发接收器
 * @author mark
 * @date 2022/8/02 15:58
 */
@Slf4j
public class ClientMsgUi {

    /**
     * 滚动面板
     */
    private JScrollPane jScrollPaneComponent;

    /**
     * 文本域
     */
    private JTextArea area;

    /**
     * 窗口
     */
    private JFrame frame;

    public void init() {
        // 创建JFrame实例
        frame = new JFrame("client");
        // 设置frame的宽高
        frame.setSize(500, 500);
        // 创建面板，这个类似于HTML的div标签我们可以创建多个面板并在JFrame中指定位置面板中我们可以添加文本字段，按钮及其他组件。
        JPanel panel = new JPanel();
        // 添加面板
        frame.add(panel);
        // 调用用户定义的方法并添加组件到面板
        placeComponents(panel);

        // 设置界面可见
        frame.setVisible(true);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
    }

    /**
     * 布局面板
     *
     * @param panel 面板
     */
    private void placeComponents(JPanel panel) {

        // 布局部分 这边设置布局为 null
        panel.setLayout(null);

        // 创建 JLabel
        JLabel userLabel = new JLabel("Command:");
        // 这个方法定义了组件的位置。 setBounds(x, y, width, height) x 和 y 指定左上角的新位置，由 width 和 height 指定新的大小。
        userLabel.setBounds(10, 20, 80, 25);
        panel.add(userLabel);

        // 创建文本域用于用户输入命令
        JTextField userCommand = new JTextField(20);
        userCommand.setBounds(100, 20, 350, 25);
        panel.add(userCommand);

        // 发送按钮
        JButton sendButton = new JButton("Send");
        sendButton.setBounds(10, 80, 80, 25);
        sendButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                String command = userCommand.getText();
                msgToClient(command);
            }
        });
        panel.add(sendButton);


        // 用于返显服务端回馈的内容
        area = new JTextArea();
        // 设置文本域不可编辑
        area.setEditable(false);
        // 设置文本域自动换行
        area.setLineWrap(true);
        // 激活断行不断字功能
        area.setWrapStyleWord(true);
        jScrollPaneComponent = new JScrollPane(area);
        jScrollPaneComponent.setBounds(30, 100, 422, 290);
        panel.add(jScrollPaneComponent);
    }

    /**
     * 收到命令下发消息后把消息转发给消息处理器进行处理
     *
     * @param msg 要给服务端发送的消息
     */
    public void msgToClient(String msg) {
        ContextUtils.getBean(MsgHandler.class).msgToClient(msg, area);
    }

}
