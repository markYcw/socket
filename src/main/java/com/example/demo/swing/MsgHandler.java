package com.example.demo.swing;

import com.example.demo.client.Client;
import com.example.demo.server.Server;
import com.example.demo.utils.ContextUtils;
import lombok.extern.slf4j.Slf4j;

import javax.swing.*;
import java.io.IOException;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author mark
 * @date 2022/7/29 15:18
 * @describe 消息处理转发类
 */
@Slf4j
public class MsgHandler {

    /**
     * 用于对客户端UI进行信息返显
     * key是客户端ID，value是进行返显的对象
     */
    private ConcurrentHashMap<String, JTextArea> areas = new ConcurrentHashMap<>();

    private static volatile MsgHandler instance;

    private MsgHandler() {
    }

    public static MsgHandler getInstance() {
        if (null == instance) {
            synchronized (MsgHandler.class) {
                if (null == instance) {
                    instance = new MsgHandler();
                }
            }
        }
        return instance;
    }

    /**
     * 处理消息后把消息转发给服务端
     *
     * @param message 消息
     */
    public void toServer(String message) {
        //判断是不是给全员发消息 --send-text-to-all第十三个字符是t为判断标准
        if (message.charAt(12) == 't') {
            sendServerAll(message);
        } else {
            sendServerSingle(message);
        }
    }

    /**
     * 处理消息后把消息转发给客户端
     *
     * @param message 消息 对于客户端总共有连接/普通聊天消息/关闭连接消息
     */
    public void toClient(String message, JTextArea area) {
        //判断是普通消息还是链接消息
        if (message.charAt(2) == 'c') {
            //连接到服务端 --connect-server 127.0.0.1 9001 01其中01是客户端id
            CompletableFuture.runAsync(() -> this.connectToServer(message, area));
        } else {
            //发送消息给服务端 发送消息格式为命令+客户端id+消息内容
            CompletableFuture.runAsync(() -> msgToServer(message));
        }
    }

    /***
     * 客户端发起连接请求连接服务端
     * @param message 链接客户端的消息
     */
    public void connectToServer(String message, JTextArea area) {
        try {
            String clientId = message.substring(29, 31);
            areas.put(clientId, area);
            ContextUtils.getBean(Client.class).connect(message.substring(16, 25), clientId, Integer.valueOf(message.substring(25, 29)));
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
    public void msgToServer(String message) {
        try {
            //先判断是不是关闭连接消息
            if (message.charAt(2) == 'd') {
                ContextUtils.getBean(Client.class).sendMsg(message.substring(19, 21), message);
            } else {
                //普通消息
                String clientId = message.substring(21, 23);
                if (message.length() > 73) {
                    //如果消息超过50个字符分两次发送
                    ContextUtils.getBean(Client.class).sendMsg(clientId, message.substring(23, 72));
                    Thread.sleep(500);
                    ContextUtils.getBean(Client.class).sendMsg(clientId, message.substring(73, message.length()));
                } else {
                    ContextUtils.getBean(Client.class).sendMsg(clientId, message.substring(23, message.length()));
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
    public void sendServerAll(String message) {
        try {
            ContextUtils.getBean(Server.class).sendAll(message);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    /**
     * 私聊功能--send-text两位字符作为客户端ID
     *
     * @param message 私聊消息内容
     */
    public void sendServerSingle(String message) {
        try {
            ContextUtils.getBean(Server.class).sendSingle(message);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }


    /**
     * 客户端返回服务端信息进行返显
     *
     * @param msg 客户端返回给服务端信息
     */
    public void clientMsg(StringBuilder msg) {
        String s = msg.toString();
        ContextUtils.getBean(ServerMsgReceiver.class).clientMsgToUi(s);
    }


    /**
     * 服务端返回客户端信息进行返显
     * 这里有两种情况一种是群发一种是私发给某个客户端所以得根据ID从channels里面查询看看是否是私发
     *
     * @param msg 服务端返回客户端信息
     */
    public void clientServerMsg(String msg) throws InterruptedException {
        String s = "server:";
        String clientId = msg.substring(0, 2);
        if (check(clientId)) {
            //如果能找到说明是私发功能
            JTextArea jTextArea = areas.get(clientId);
            jTextArea.append(s + msg);
        } else {
            //群发消息
            Iterator<Map.Entry<String, JTextArea>> iterator = areas.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<String, JTextArea> entry = iterator.next();
                JTextArea area = entry.getValue();
                String m = s + msg;
                area.append(s + m);
            }
        }

    }

    /***
     * 判断此信道是否在容器中存在，如果存在则返回ture，如果不存在则返回false
     * @return
     */
    public Boolean check(String clientId) {
        Iterator<Map.Entry<String, JTextArea>> iterator = areas.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, JTextArea> entry = iterator.next();
            String id = entry.getKey();
            if (id.equals(clientId)) {
                return true;
            }
        }
        return false;
    }
}
