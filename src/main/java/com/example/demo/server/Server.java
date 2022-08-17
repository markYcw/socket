package com.example.demo.server;

import com.example.demo.swing.MsgHandler;
import com.example.demo.utils.ContextUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author mark
 * @date 2022/7/29 15:23
 * @describe socket通信服务端
 */
@Slf4j
@Component
public class Server {
    /**
     * ByteBuffer 字节缓冲区用于写信息给客户端
     */
    private final ByteBuffer buffer = ByteBuffer.allocate(70);

    /**
     * 工作线程专门用于处理读写事件
     */
    private Worker worker;

    /**
     * 开启服务端
     *
     * @throws IOException IO异常
     */
    public void startServer() throws IOException {
        ServerSocketChannel ssc = ServerSocketChannel.open();
        ssc.configureBlocking(false);
        // 专用于链接事件
        Selector boss = Selector.open();
        ssc.register(boss, SelectionKey.OP_ACCEPT);
        ssc.bind(new InetSocketAddress(9998));
        // 创建固定数量的worker
        worker = new Worker("worker=0");
        while (true) {
            boss.select();
            Iterator<SelectionKey> iterator = boss.selectedKeys().iterator();
            while (iterator.hasNext()) {
                SelectionKey key = iterator.next();
                iterator.remove();
                if (key.isAcceptable()) {
                    SocketChannel sc = ssc.accept();
                    sc.configureBlocking(false);
                    log.info("===connected...{}", sc.getRemoteAddress());
                    // 2关联selector
                    log.info("=====beforeRegister{}", sc.getRemoteAddress());
                    worker.register(sc); //boss调用 初始化worker的selector 启动线程
                    log.info("====afterRegister{}", sc.getRemoteAddress());
                }
            }
        }
    }

    /**
     * 向全员发消息相当于群聊功能
     *
     * @param message 群聊消息 --send-text-to-all
     * @throws InterruptedException 中断异常
     */
    public void sendMsgToAll(String message) throws InterruptedException {
        String msgLength = message.substring(0, 2);
        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
        Iterator<Map.Entry<String, Integer>> iterator = worker.getClients().entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Integer> entry = iterator.next();
            Integer port = entry.getValue();
            SocketChannel socketChannel = worker.getSockets().get(port);
            if (bytes.length > 70) {
                sendMsgToClient(msgLength + message.substring(20, 69), socketChannel);
                Thread.sleep(500);
                sendMsgToClient(message.substring(70, message.length()), socketChannel);
            } else {
                sendMsgToClient(msgLength + message.substring(20, message.length()), socketChannel);
            }
        }
    }

    /**
     * 私聊功能 私聊功能：消息长度+--send-text两位字符作为客户端ID
     *
     * @param message 服务端给客户端私发消息是携带客户端id的(clientId+message)
     * @throws InterruptedException 中断异常
     */
    public void sendMsgToSingle(String message) throws InterruptedException {
        String msgLength = message.substring(0, 2);
        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
        // 先提取客户ID
        String id = message.substring(13, 15);
        if (worker.getClients().get(id) == null) {
            return;
        }
        Integer port = worker.getClients().get(id);
        SocketChannel socketChannel = worker.getSockets().get(port);
        if (bytes.length > 67) {
            sendMsgToClient(msgLength + message.substring(13, 64), socketChannel);
            Thread.sleep(500);
            sendMsgToClient(msgLength + id + message.substring(64, message.length()), socketChannel);
        } else {
            sendMsgToClient(msgLength + message.substring(13, message.length()), socketChannel);
        }


    }

    /**
     * 发送消息给客户端
     *
     * @param msg           消息正文
     * @param socketChannel 客户端信道
     */
    public void sendMsgToClient(String msg, SocketChannel socketChannel) {
        buffer.clear();
        buffer.put(msg.getBytes(StandardCharsets.UTF_8));
        buffer.flip();
        try {
            socketChannel.write(buffer);
        } catch (IOException e) {
            log.error("===========发送消息给客户端失败{}", e);
        }
    }
}

@Slf4j
class Worker implements Runnable {

    /**
     * 此容器用于判断客户端是否已经登录，如果第一次登录则会保存客户端ID和socketChannel的端口
     * 保存客户端ID和对应的socketChannel的关系的容器key是客户端ID，value是socketChannel的端口
     */
    private ConcurrentHashMap<String, Integer> clients = new ConcurrentHashMap<>();

    /**
     * 此容器用于根据端口找到其对应的SocketChannel 因为服务端要发消息给客户端的时候需要根据客户端ID对应的端口找到对应的socketChannel进行发送
     * 保存socketChannel的端口和socketChannel的关系的容器key是端口，value是socketChannel
     */
    private ConcurrentHashMap<Integer, SocketChannel> sockets = new ConcurrentHashMap<>();


    /**
     * 此容器用于服务端主动关闭客户端链接 根据端口找到对应的key，然后调用key.cancel关闭客户端连接
     * 保存SelectionKey和对应socketChannel的关系，key是客户端ID对应客户端端口，value是SelectionKey
     */
    private ConcurrentHashMap<Integer, SelectionKey> keys = new ConcurrentHashMap<>();

    /**
     * 此容器用于保存客户端给服务端发超过50个字符的消息
     * key是消息长度，value是消息
     */
    private ConcurrentHashMap<Integer, String> msgPool = new ConcurrentHashMap<>();

    /**
     * 用于启动worker
     */
    private Thread thread;

    /**
     * 多路复用选择器
     */
    private Selector selector;

    /**
     * 工作线程名称
     */
    private String name;

    private final ByteBuffer buffer = ByteBuffer.allocate(70);

    /**
     * 线程是否启动
     */
    private volatile boolean start = false;

    public Worker(String name) {
        this.name = name;
    }

    /**
     * 获取客户端map
     *
     * @return
     */
    public ConcurrentHashMap<String, Integer> getClients() {
        return clients;
    }

    /**
     * 获取信道map
     *
     * @return
     */
    public ConcurrentHashMap<Integer, SocketChannel> getSockets() {
        return sockets;
    }


    /**
     * 注册读写事件
     *
     * @param sc SocketChannel
     * @throws IOException IO异常
     */
    public void register(SocketChannel sc) throws IOException {
        if (!start) {
            selector = Selector.open();
            thread = new Thread(this, name);
            thread.start();
            start = true;
        }
        selector.wakeup();// 唤醒selector
        sc.register(selector, SelectionKey.OP_READ);
    }

    /**
     * 读取客户端消息 客户端第一个消息格式必须是登录消息，消息格式为：login+客户端ID，维护端ID为2位
     *
     * @param source 用来读取消息的buffer
     * @param key    SelectionKey
     * @throws IOException IO异常
     */
    public void readMsg(ByteBuffer source, SelectionKey key, SocketChannel channel) throws IOException {
        source.flip();
        // 判断是否登录消息，如果第一个不是登录消息则断开与客户端连接
        // 读取readBuf数据 然后打印数据
        byte[] bytes = new byte[source.remaining()];
        source.get(bytes);
        String s = new String(bytes);
        // 现在只有两种情况：第一种情况这个消息为登录消息消息,格式为：login+clientId，另一种情况:此消息是个普通消息
        String msg = s.substring(0, 5);
        InetSocketAddress address = (InetSocketAddress) channel.getRemoteAddress();
        int port = address.getPort();
        if (msg.equals("login")) {
            // 如果是登录消息则无需判断消息长度因为登录消息不会大于50个字符--connect-server127.0.0.1999801
            // 如果是登录消息判断是不是第一次登录消息，如果不是第一次登录根据这个ID肯定是在容器里能找到信道，则断开连接
            // 获取客户端ID
            String clientId = s.substring(5, 7);
            Integer clientPort = clients.get(clientId);
            if (clientPort != null) {
                // 如果如果收到重复的clientId，则断开前一个连接
                log.info("==========同一个客户端登录断开之前客户端连接=======");
                SelectionKey selectionKey = keys.get(clientPort);
                selectionKey.cancel();
                keys.remove(clientPort);
                sockets.remove(port);
                clients.put(clientId, port);
                sockets.put(port, channel);
                keys.put(port, key);
            } else {
                // 此情况为用户第一次登录，则存储用户信息
                clients.put(clientId, port);
                keys.put(port, key);
                sockets.put(port, channel);
                // 服务端需显示客户端连接、断开信息，如：“client1 has connected”、“client1 has disconnected”
                StringBuilder m = new StringBuilder("");
                m.append("client" + clientId + " has connected");
                log.info("========客户端登录" + m);
                ContextUtils.getBean(MsgHandler.class).clientMsgToServerUi(m);
            }
        } else {
            // 如果不是登录消息首先获取此次消息长度判断是否大于50个字符，如果小于50则立即给服务端UI进行返显，否则需要等消息全部收完再进行返显
            // 如果不是登录消息则判断这个信道是否在容器里如果不在容器里说明他发的第一个消息不是登录消息则断开连接，如果在容器里则有两种情况
            // 第一种情况是个普通消息，则给ServerMsgReceiver返回。另一种情况这个消息是个主动断开连接消息--disconnect-server+clientId
            Integer msgLength = Integer.valueOf(s.substring(0, 2));
            if (checkClient(port)) {
                // 如果存在给ServerMsgReceiver返回
                String clientId = getClientId(port);
                StringBuilder message = new StringBuilder("");
                source.rewind();
                for (int k = 0; k < 5; k++) {
                    byte b = source.get(k);
                    message.append((char) b);
                }
                if (message.charAt(4) == 'd') {
                    // 如果是断开客户端连接就是断开相应的客户端连接
                    offLine(channel);
                } else {
                    // 如果是普通消息就进行返显
                    source.rewind();
                    // 要给ui进行返显的消息
                    StringBuilder m = new StringBuilder("");
                    for (int j = 0; j < source.limit(); j++) {
                        byte b = source.get(j);
                        m.append((char) b);
                    }
                    // 消息加上客户端ID
                    String id = clientId + ":";
                    // 判断消息长度判断是否大于50个字符，如果小于50则立即给服务端UI进行返显，否则需要等消息全部收完再进行返显
                    if (msgLength > 50) {
                        String mes = msgPool.get(msgLength);
                        if (StringUtils.isEmpty(mes)) {
                            msgPool.put(msgLength, m.substring(2));
                        } else {
                            String tolMsg = mes + m.substring(2);
                            StringBuilder totalMsg = new StringBuilder(id + tolMsg);
                            ContextUtils.getBean(MsgHandler.class).clientMsgToServerUi(totalMsg);
                            //消费完消息去除消息池里面内容
                            msgPool.remove(msgLength);
                        }
                    } else {
                        //如果小于50个字符则直接进行返显
                        StringBuilder str = new StringBuilder(id + m.substring(2));
                        ContextUtils.getBean(MsgHandler.class).clientMsgToServerUi(str);
                    }
                }

            } else {
                key.cancel();
            }
        }
        source.clear();
    }

    /***
     * 判断此客户端是否已经登录，如果登录则返回ture，否则返回false
     * clientPort 客户端端口
     * @return
     */
    public Boolean checkClient(Integer clientPort) throws IOException {
        Iterator<Map.Entry<String, Integer>> iterator = clients.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Integer> entry = iterator.next();
            Integer port = entry.getValue();
            if (port.equals(clientPort)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 根据客户端端口得到对应的客户端ID
     *
     * @param clientPort 客户端端口
     * @return
     * @throws IOException
     */
    public String getClientId(Integer clientPort) throws IOException {
        Iterator<Map.Entry<String, Integer>> iterator = clients.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Integer> entry = iterator.next();
            Integer port = entry.getValue();
            if (port.equals(clientPort)) {
                return entry.getKey();
            }
        }
        return null;
    }

    /**
     * 移除下线的客户端
     *
     * @param socketChannel 要下线的信道
     */
    public void offLine(SocketChannel socketChannel) throws IOException {
        InetSocketAddress address = (InetSocketAddress) socketChannel.getRemoteAddress();
        Integer port = address.getPort();
        SelectionKey key = keys.get(port);
        key.cancel();
        keys.remove(socketChannel);
        // 服务端需显示客户端连接、断开信息，如：“client1 has connected”、“client1 has disconnected”
        StringBuilder m = new StringBuilder("");
        String clientId = getClientId(port);
        m.append("client" + clientId + " has disconnected");
        ContextUtils.getBean(MsgHandler.class).clientMsgToServerUi(m);
        Iterator<Map.Entry<String, Integer>> iterator = clients.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Integer> entry = iterator.next();
            Integer clientPort = entry.getValue();
            if (port.equals(clientPort)) {
                clients.remove(entry.getKey());
                sockets.remove(port);
            }
        }
    }


    @Override
    public void run() {
        try {
            while (true) {
                selector.select();
                Iterator<SelectionKey> iterator = selector.selectedKeys().iterator();
                while (iterator.hasNext()) {
                    SelectionKey key = iterator.next();
                    if (!key.isValid()) {
                        continue;
                    }
                    iterator.remove();
                    if ((key.isReadable())) {
                        try {
                            SocketChannel channel = (SocketChannel) key.channel();
                            int read = channel.read(buffer); // 如果客户端是正常断开的话，read方法的返回值是-1
                            if (read == -1) {
                                key.cancel();
                                // 从client中移除下线的客户端
                                offLine(channel);
                            } else {
                                readMsg(buffer, key, channel);
                            }
                        } catch (IOException e) {
                            e.printStackTrace();
                            // 如果客户端被强制关闭那么把key从selectedKey集合中移除
                        }
                    }
                }
            }
        } catch (IOException e) {
            log.error("=====读取客户端消息时发生异常{}", e);

        }
    }
}
