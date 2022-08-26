package com.example.demo.server;

import com.example.demo.swing.MsgHandler;
import com.example.demo.utils.ContextUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
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
                    //boss调用 初始化worker的selector 启动线程
                    worker.register(sc);
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
        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
        Iterator<Map.Entry<String, Integer>> iterator = worker.getClients().entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Integer> entry = iterator.next();
            Integer port = entry.getValue();
            SocketChannel socketChannel = worker.getSockets().get(port);
            if (bytes.length > 68) {
                sendMsgToClient(message.substring(18, 67), socketChannel);
                sendMsgToClient(message.substring(68, message.length()), socketChannel);
            } else {
                sendMsgToClient(message.substring(18, message.length()), socketChannel);
            }
        }
    }

    /**
     * 私聊功能 私聊功能：--send-text两位字符作为客户端ID
     *
     * @param message 服务端给客户端私发消息是携带客户端id的(clientId+message)
     * @throws InterruptedException 中断异常
     */
    public void sendMsgToSingle(String message) throws InterruptedException {
        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
        // 先提取客户ID
        String id = message.substring(11, 13);
        if (worker.getClients().get(id) == null) {
            return;
        }
        Integer port = worker.getClients().get(id);
        SocketChannel socketChannel = worker.getSockets().get(port);
        if (bytes.length > 65) {
            sendMsgToClient(message.substring(11, 62), socketChannel);
            sendMsgToClient(id + message.substring(62, message.length()), socketChannel);
        } else {
            sendMsgToClient(id + message.substring(13, message.length()), socketChannel);
        }


    }

    /**
     * 发送消息给客户端
     *
     * @param msg           消息正文
     * @param socketChannel 客户端信道
     */
    private void sendMsgToClient(String msg, SocketChannel socketChannel) {
        String message = addPacketLength(msg);
        buffer.clear();
        buffer.put(message.getBytes(StandardCharsets.UTF_8));
        buffer.flip();
        try {
            socketChannel.write(buffer);
        } catch (IOException e) {
            log.error("===========发送消息给客户端失败{}", e);
        }
    }

    private String addPacketLength(String msg) {
        if (msg.length() < 10) {
            msg = "0" + msg.length() + msg;
        } else {
            msg = msg.length() + msg;
        }
        return msg;
    }
}

@Slf4j
class Worker implements Runnable {

    /**
     * 此容器用于判断客户端是否已经登录，如果第一次登录则会保存客户端ID和socketChannel的端口
     * 保存客户端ID和对应的socketChannel的关系的容器key是客户端ID，value是socketChannel的端口
     */
    private final ConcurrentHashMap<String, Integer> clients = new ConcurrentHashMap<>();

    /**
     * 此容器用于根据端口找到其对应的SocketChannel 因为服务端要发消息给客户端的时候需要根据客户端ID对应的端口找到对应的socketChannel进行发送
     * 保存socketChannel的端口和socketChannel的关系的容器key是端口，value是socketChannel
     */
    private final ConcurrentHashMap<Integer, SocketChannel> sockets = new ConcurrentHashMap<>();


    /**
     * 此容器用于服务端主动关闭客户端链接 根据端口找到对应的key，然后调用key.cancel关闭客户端连接
     * 保存SelectionKey和对应socketChannel的关系，key是客户端ID对应客户端端口，value是SelectionKey
     */
    private final ConcurrentHashMap<Integer, SelectionKey> keys = new ConcurrentHashMap<>();

    /**
     * 此容器用于保存未完整读取的消息（解决TCP粘包、拆包）
     * key是信道，value是ByteBuffer
     */
    private final ConcurrentHashMap<SocketChannel, ByteBuffer> byteBuffers = new ConcurrentHashMap<>();

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

    /**
     * 登录指令前两位字符
     */
    private static final String LO = "lo";

    /**
     * 用于读取客户端发来的消息或者写消息给客户端
     */
    private final ByteBuffer buffer = ByteBuffer.allocate(100);

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
        // 唤醒selector
        selector.wakeup();
        sc.register(selector, SelectionKey.OP_READ);
    }

    /**
     * 判断上一次是否出现拆包粘包情况
     *
     * @param source 用来读取消息的buffer
     * @param key    SelectionKey
     * @throws IOException IO异常
     */
    private void dealBytebuffer(ByteBuffer source, SelectionKey key, SocketChannel channel) throws IOException {
        // 获取端口
        InetSocketAddress address = null;
        try {
            address = (InetSocketAddress) channel.getRemoteAddress();
        } catch (IOException e) {
            e.printStackTrace();
        }
        int port = address.getPort();
        // 判断上一次是否发生拆包现象。
        if (byteBuffers.containsKey(channel)) {
            // 如果消息池里有这个key说明发生了拆包或者粘包的现象需要把缓存的Bytebuffer取出
            ByteBuffer cacheBuffer = byteBuffers.get(channel);
            // 把两个包合在一起
            cacheBuffer.put(source);
            // 读取包数据
            dealMsg(port,cacheBuffer,channel,key);
        }  else {
            // 如果没有发生拆包粘包现象则正常读取
            dealMsg(port,source, channel, key);
        }
    }

    /**
     * 处理包数据
     *
     * @param buffer ByteBuffer
     * @param channel 信道
     * @param key SelectionKey
     * @param port 端口
     */
    private void dealMsg(Integer port, ByteBuffer buffer, SocketChannel channel, SelectionKey key) {
        // 切换读模式
        buffer.flip();
        // 读取readBuf数据 然后打印数据
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        String msg = new String(bytes);
        // 首先确定消息长度
        Integer msgLength = Integer.valueOf(msg.substring(0,2));
        // 得到第一个消息
        String firstMsg = msg.substring(2,2+msgLength);
        // 现在只有两种情况：第一种情况这个消息为登录消息消息,格式为：login+clientId，另一种情况:此消息是个普通消息
        String m = firstMsg.substring(0, 2);
        // 第一个消息只能是登录消息消息格式为：login+clientId。所以前两个字母只能是lo。
        if (m.equals(LO)) {
            login(firstMsg, port, channel, key);
        } else {
            handleChatMsg(firstMsg, port, channel, key);
        }
        // 包长度减2位包头长度得到包可读长度，现在判断包可读长度和消息长度的差值
        // 如果差值等于0说明包刚好读完跳出循环
        // 如果差值小于0则说明是拆包情况则把包进行缓存并跳出循环
        // 如果差值大于0则说明发生粘包情况则递归调用本方法
        Integer remain = buffer.limit()-2-msgLength;
        String remainMsg = "";
        if (remain != 0){
            // 先得到剩余包：剩余的包 = 总消息 - 已读包
            remainMsg = msg.substring(2+firstMsg.length());
        }
        if (remain == 0) {
            buffer.clear();
            return;
        }else if(remain < 0){
            // 先得到剩余包：剩余的包 = 总消息 - 已读包
            // 把剩余的包放进缓存
            ByteBuffer cacheBuffer = ByteBuffer.allocate(100);
            cacheBuffer.put(remainMsg.getBytes(StandardCharsets.UTF_8));
            byteBuffers.put(channel,cacheBuffer);
            buffer.clear();
            return;
        } else{
            // 先得到剩余包：剩余的包 = 总消息 - 已读包
            // 然后递归调用此方法进行拆包处理
            buffer.clear();
            buffer.put(remainMsg.getBytes(StandardCharsets.UTF_8));
            dealMsg(port,buffer,channel,key);
        }
    }

    /**
     * 处理普通消息
     *
     * @param message 聊天消息
     * @param port    客户端端口
     * @param channel 信道
     * @param key     SelectionKey
     */
    private void handleChatMsg(String message, Integer port, SocketChannel channel, SelectionKey key) {
        // 如果不是登录消息则判断这个信道是否在容器里如果不在容器里说明他发的第一个消息不是登录消息则断开连接，如果在容器里则有两种情况
        // 第一种情况是个普通消息，则给ServerMsgReceiver返回。另一种情况这个消息是个主动断开连接消息--disconnect-server+clientId
        if (checkClient(port)) {
            // 如果存在给ServerMsgReceiver返回
            String clientId = getClientId(port);
            if (message.charAt(4) == 'd') {
                // 如果是断开客户端连接就是断开相应的客户端连接
                offLine(channel);
            } else {
                // 如果是普通消息就进行返显
                // 消息加上客户端ID
                String id = clientId + ":";
                StringBuilder totalMsg = new StringBuilder(id + message);
                ContextUtils.getBean(MsgHandler.class).clientMsgToServerUi(totalMsg);
            }
        } else {
            // 这种情况说明他发的第一个消息不是登录消息需要直接断开连接
            key.cancel();
        }
    }

    /**
     * 登录服务端方法
     *
     * @param msg     登录消息
     * @param port    客户端端口
     * @param channel 信道
     * @param key     SelectionKey
     */
    private void login(String msg, Integer port, SocketChannel channel, SelectionKey key) {
        // 判断是不是第一次登录消息，如果不是第一次登录根据这个ID肯定是在容器里能找到信道，则断开连接
        // 获取客户端ID
        String clientId = msg.substring(5, 7);
        Integer clientPort = clients.get(clientId);
        if (clientPort != null) {
            // 如果如果收到重复的clientId，则断开前一个连接,并只保留现在这个连接
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
    }


    /***
     * 判断此客户端是否已经登录，如果登录则返回ture，否则返回false
     * clientPort 客户端端口
     * @return
     */
    private Boolean checkClient(Integer clientPort) {
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
    private String getClientId(Integer clientPort) {
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
    private void offLine(SocketChannel socketChannel) {
        InetSocketAddress address = null;
        try {
            address = (InetSocketAddress) socketChannel.getRemoteAddress();
        } catch (IOException e) {
            e.printStackTrace();
        }
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
                byteBuffers.remove(socketChannel);
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
                            // 如果客户端是正常断开的话，read方法的返回值是-1
                            int read = channel.read(buffer);
                            if (read == -1) {
                                key.cancel();
                                // 从client中移除下线的客户端
                                offLine(channel);
                            } else {
                                dealBytebuffer(buffer, key, channel);
                            }
                        } catch (IOException e) {
                            log.error("===========客户端断开了连接~~");
                            // 如果客户端被强制关闭那么把key从selectedKey集合中移除
                            key.cancel();
                            SocketChannel channel = (SocketChannel) key.channel();
                            offLine(channel);
                        }
                    }
                }
            }
        } catch (IOException e) {
            log.error("=====读取客户端消息时发生异常{}", e);
        }
    }
}
