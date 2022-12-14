package com.example.demo.swing;

import com.example.demo.client.Client;
import com.example.demo.server.Server;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.io.IOException;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author mark
 * @describe 消息处理转发类
 * @date 2022/7/29 15:18
 */
@Slf4j
@Component
public class MsgHandler {

    @Resource
    private Client client;

    @Resource
    private Server server;

    @Resource
    private ChatServerUi chatServerUi;


    /**
     * 用于对客户端UI进行信息返显
     * key是客户端ID，value是进行返显的对象
     */
    private final ConcurrentHashMap<String, ChatClientUi> chatUis = new ConcurrentHashMap<>();

    /**
     * 处理消息后把消息转发给服务端
     *
     * @param message 消息
     */
    public void dealMsg(String message) {
        // 判断是不是给全员发消息 --send-text-to-all第十三个字符是t为判断标准
        if (message.charAt(12) == 't') {
            CompletableFuture.runAsync(() -> sendAllMsgToServer(message));
        } else {
            CompletableFuture.runAsync(() -> sendSingleMsgToServer(message));
        }
    }

    /**
     * 处理消息后把消息转发给客户端
     *
     * @param message 消息 对于客户端总共有连接/普通聊天消息/关闭连接消息
     */
    public void msgToClient(String message, ChatClientUi ui) {
        // 判断是普通消息还是链接消息
        if (message.charAt(2) == 'c') {
            String clientId = message.substring(29, 31);
            // 连接到服务端 发送消息格式为+2位包数据长度信息加实际要发送的消息--connect-server 127.0.0.1 9001 01其中01是客户端id
            CompletableFuture.runAsync(() -> this.connectToServer(message, clientId)
            );
            chatUis.put(clientId, ui);
        } else {
            // 发送消息给服务端 发送消息格式为命令+客户端id+消息内容
            CompletableFuture.runAsync(() -> msgToServer(message));
        }
    }

    /***
     * 客户端发起连接请求连接服务端
     * @param message 链接客户端的消息
     */
    public void connectToServer(String message, String clientId) {
        try {
            client.connect(message.substring(16, 25), clientId, Integer.valueOf(message.substring(25, 29)));
        } catch (IOException e) {
            log.error("=======客户端连接到服务端异常{}", e);
        }
    }

    /**
     * 客户端给服务端发送消息
     * 消息有两种一种是通信消息--send-text-to-server+clientId+hello!，另一种是关闭连接消息--disconnect-server+clientId
     *
     * @param message 客户端给服务端发送消息
     */
    private void msgToServer(String message) {
        try {
            // 先判断是不是关闭连接消息
            if (message.charAt(2) == 'd') {
                client.sendMsgToServer(message.substring(19, 21), message);
            } else {
                // 普通消息
                String clientId = message.substring(21, 23);
                if (message.length() > 75) {
                    // 如果消息超过50个字符分两次发送 发送消息格式为：消息长度+消息正文
                    // 提取消息正文
                    client.sendMsgToServer(clientId, message.substring(23, 74));
                    // 提取消息正文
                    client.sendMsgToServer(clientId, message.substring(73, message.length()));
                } else {
                    // 提取消息正文
                    client.sendMsgToServer(clientId, message.substring(23, message.length()));
                }

            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 群聊功能 --send-text-to-all
     *
     * @param message 群聊消息
     */
    public void sendAllMsgToServer(String message) {
        try {
            server.sendMsgToAll(message);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    /**
     * 私聊功能--send-text两位字符作为客户端ID
     *
     * @param message 私聊消息内容
     */
    public void sendSingleMsgToServer(String message) {
        try {
            server.sendMsgToSingle(message);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }


    /**
     * 客户端返回服务端信息进行返显
     *
     * @param msg 客户端返回给服务端信息
     */
    public void clientMsgToServerUi(StringBuilder msg) {
        String s = msg.toString();
        chatServerUi.clientMsgToUi(s);
    }


    /**
     * 服务端返回客户端信息进行返显
     * 这里有两种情况一种是群发一种是私发给某个客户端所以得根据ID从channels里面查询看看是否是私发
     *
     * @param msg 服务端返回客户端信息
     */
    public void serverMsgToClientUi(String msg) throws InterruptedException {
        String clientId = msg.substring(0, 2);
        if (checkClient(clientId)) {
            // 如果能根据客户端ID找到对应客户端则说明是私发功能，否则是群发消息
            ChatClientUi ui = chatUis.get(clientId);
            ui.serverMsgToUi(msg);
        } else {
            // 群发消息
            Iterator<Map.Entry<String, ChatClientUi>> iterator = chatUis.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<String, ChatClientUi> entry = iterator.next();
                ChatClientUi ui = entry.getValue();
                ui.serverMsgToUi(msg);
            }
        }

    }

    /***
     * 判断此客户端是否在容器中存在，如果存在则返回ture，如果不存在则返回false
     * @return
     */
    private Boolean checkClient(String clientId) {
        Iterator<Map.Entry<String, ChatClientUi>> iterator = chatUis.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, ChatClientUi> entry = iterator.next();
            String id = entry.getKey();
            if (id.equals(clientId)) {
                return true;
            }
        }
        return false;
    }
}
