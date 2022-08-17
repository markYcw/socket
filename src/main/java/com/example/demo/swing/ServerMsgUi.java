package com.example.demo.swing;

import ch.qos.logback.core.util.ContextUtil;
import com.example.demo.utils.ContextUtils;
import org.springframework.stereotype.Component;

import javax.swing.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

/**
 * @author mark
 * @describe 服务端消息下发接收器
 * @date 2022/7/29 15:58
 */
@Component
public class ServerMsgUi {

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
        // 创建 JFrame 实例
        frame = new JFrame("server");
        // Setting the width and height of frame
        frame.setSize(500, 500);
        // 创建面板，这个类似于 HTML 的 div 标签 我们可以创建多个面板并在 JFrame 中指定位置  面板中我们可以添加文本字段，按钮及其他组件。
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
     * 将组件添加到面板
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
        JTextField userCommand = new JTextField(100);
        userCommand.setBounds(100, 20, 350, 25);
        panel.add(userCommand);

        // 发送按钮
        JButton sendButton = new JButton("Send");
        sendButton.setBounds(10, 80, 80, 25);
        sendButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                String command = userCommand.getText();
                toServer(command);
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
     * 客户端返回的信息进行返显
     *
     * @param msg 客户端返回给的信息
     */
    public void clientMsgToUi(String msg) {
        area.append(msg);
    }

    /**
     * 收到命令下发消息后把消息转发给消息处理器进行处理
     *
     * @param msg 给服务端下发的信息内容
     */
    public void toServer(String msg) {
        ContextUtils.getBean(MsgHandler.class).dealMsg(msg);
    }

}
