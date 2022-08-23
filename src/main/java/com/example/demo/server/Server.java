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
    private void sendMsgToClient(String msg, SocketChannel socketChannel) {
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
     * 此容器用于保存未完整读取的消息（解决TCP粘包、拆包）
     * key是信道，value是已经读取的消息，但此容器还要配合remainMsgLength这个属性使用
     */
    private ConcurrentHashMap<SocketChannel, String> readMsgPool = new ConcurrentHashMap<>();

    /**
     * 此容器用于保存未完整读取的消息（解决TCP粘包、拆包）
     * key是信道，value是已经读取的消息的长度的第一位
     */
    private ConcurrentHashMap<SocketChannel, String> halfMsgLength = new ConcurrentHashMap<>();

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
     * 此变量用于记录还需要读取的长度
     */
    private Integer remainMsgLength = 0;

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
        // 唤醒selector
        selector.wakeup();
        sc.register(selector, SelectionKey.OP_READ);
    }

    /**
     * 读取客户端消息 客户端第一个消息格式必须是登录消息，消息格式为：login+客户端ID，维护端ID为2位
     *
     * @param source 用来读取消息的buffer
     * @param key    SelectionKey
     * @throws IOException IO异常
     */
    private void readMsg(ByteBuffer source, SelectionKey key, SocketChannel channel) throws IOException {
        source.flip();
        // 获取端口
        InetSocketAddress address = null;
        try {
            address = (InetSocketAddress) channel.getRemoteAddress();
        } catch (IOException e) {
            e.printStackTrace();
        }
        int port = address.getPort();
        // 读取readBuf数据 然后打印数据
        byte[] bytes = new byte[source.remaining()];
        Integer readableLength = bytes.length;
        source.get(bytes);
        String s = new String(bytes);
        // 判断此次消息的头两位是否是包头。这时分三种情况：第一种就是这是这次没有发生粘包拆包现象这种直接读取就好了。第二种就是上一次发生粘包现象。
        // 第三种情况是上一次发生拆包现象。
        if (readMsgPool.containsKey(channel)) {
            // 如果消息池里有这个key说明发生了拆包或者粘包的现象需要把消息池里消息取出
            // 这种情况是上一次读取了部分内容
            String readMsg = readMsgPool.get(channel);
            readRemainMsg(readableLength, port, s, readMsg, channel, key);
        } else if (halfMsgLength.containsKey(channel)) {
            // 如果在这个半个消息长度的容器里面能找到说明上一次只读取到消息头部的第一个字节
            // 取出容器记录的头部消息第一位
            String msgLengthFirst = halfMsgLength.get(channel);
            // 获取头部消息第二位
            String msgLengthSecond = s.substring(0, 1);
            // 头部消息长度字符串
            String msgLength = msgLengthFirst + msgLengthSecond;
            // 这时未读字节就是这个消息的长度
            remainMsgLength = Integer.valueOf(msgLength);
            // 截取消息这时候的消息要把第一位头部消息截掉，剩余的消息读取方法和第一种年报拆包方法一样
            String msg = s.substring(1);
            readRemainMsg(readableLength - 1, port, msg, "", channel, key);
        } else {
            // 如果没有发生拆包粘包现象则正常读取
            remainMsgLength = Integer.valueOf(s.substring(0, 2));
            readRemainMsg(readableLength - 2, port, s.substring(2), "", channel, key);
        }

        source.clear();
    }

    /**
     * 上一次发生粘包，这一次读取剩余内容消息
     *
     * @param readableLength 可读字节长度
     * @param msg            本次读取的全部消息
     * @param readMsg        上一次读取的消息内容
     * @param channel        信道
     * @param key            SelectionKey
     */
    private void readRemainMsg(Integer readableLength, Integer port, String msg, String readMsg, SocketChannel channel, SelectionKey key) {
        // 截取上一次剩余消息
        String remainMsg = null;
        try {
            remainMsg = msg.substring(0, remainMsgLength);
        } catch (Exception e) {
            e.printStackTrace();
        }
        String totalMsg = readMsg + remainMsg;
        // 现在只有两种情况：第一种情况这个消息为登录消息消息,格式为：login+clientId，另一种情况:此消息是个普通消息
        String m = totalMsg.substring(0, 2);
        if (m.equals("lo")) {
            login(totalMsg, port, channel, key);
        } else {
            readChatMsg(totalMsg, port, channel, key);
        }
        Integer remain = readableLength - remainMsgLength;
        // 判断本次是否还有消息可读,如果未读消息长度等于可读消息长度的话说明可以退出
        if (remain == 0) {
            return;
        }
        // 如果除去已读内容剩余可读内容只剩一个字节
        if (remain == 1) {
            // 记录本次读到的包头第一位内容
            halfMsgLength.put(channel, msg.substring(readableLength - 1));
        }
        // 读取剩余消息包头
        Integer newMsgLength = Integer.valueOf(msg.substring(remainMsgLength, remainMsgLength + 2));
        // 剩余可读字节数
        Integer remainReadable = readableLength - remainMsgLength - 2;
        // 如果新消息长度等于剩余可读消息长度则直接读
        if (remainReadable == newMsgLength) {
            readChatMsg(totalMsg, port, channel, key);
        } else if (newMsgLength < remainReadable) {
            // 如果新消息长度小于剩余可读长度说明又发生了粘包现象
            // 这时候又分三种情况第一种情况是剩余未读消息里只包含一位下一个新消息的包头的第一位数字
            // 第二种情况是剩余未读消息里包含了下一次新消息包头信息
            // 第三种情况是剩余未读消息包含了下一次新消息包头信息和部分消息体
            if (remainReadable - newMsgLength == 1) {
                // 第一种情况
                // 要进行返显的信息
                String s = msg.substring(remainMsgLength + 2, readableLength - 1);
                readChatMsg(s, port, channel, key);
                // 把下一次新消息的头部第一个字节放入容器
                halfMsgLength.put(channel, msg.substring(readableLength - 1));
            } else if (remainReadable - newMsgLength == 2) {
                // 第二种情况
                String s = msg.substring(remainMsgLength + 2, readableLength - 2);
                readChatMsg(s, port, channel, key);
                // 记录剩余未读字节数
                remainMsgLength = Integer.valueOf(msg.substring(readableLength - 2));
                readMsgPool.put(channel, "");
            } else {
                // 第三种情况剩余未读消息包含了下一次新消息包头信息和部分消息体
                String s = msg.substring(remainMsgLength + 2, newMsgLength);
                readChatMsg(s, port, channel, key);
                // 读取下一个消息包头
                Integer nextMsgLength = Integer.valueOf(msg.substring(remainMsgLength + 2 + newMsgLength), remainMsgLength + 2 + newMsgLength + 2);
                // 读取下一个消息的部分消息,并把它放入容器
                String nextMsg = msg.substring(remainMsgLength + 2 + newMsgLength + 2);
                readMsgPool.put(channel, nextMsg);
                // 已读取下一个消息的长度为：可读长度 - （下一个消息头部索引 +2）
                Integer readNextMsgLength = readableLength - (remainMsgLength + 2 + newMsgLength + 2);
                // 重置剩余未读内容长度
                remainMsgLength = nextMsgLength - readNextMsgLength;
            }
        } else {
            // 如果新消息长度大于剩余可读长度说明又发生了拆包现象
            // 先把本次读取到的部分消息存储到容器
            String s = msg.substring(remainMsgLength + 2);
            readMsgPool.put(channel, s);
            //剩余未读字节数 = 包头 - 剩余可读字节数
            remainMsgLength = newMsgLength - remainReadable;
        }
    }

    /**
     * 读取普通聊天消息
     *
     * @param message 聊天消息
     * @param port    客户端端口
     * @param channel 信道
     * @param key     SelectionKey
     */
    private void readChatMsg(String message, Integer port, SocketChannel channel, SelectionKey key) {
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
                                readMsg(buffer, key, channel);
                            }
                        } catch (IOException e) {
                            e.printStackTrace();
                            // 如果客户端被强制关闭那么把key从selectedKey集合中移除
                            key.cancel();
                        }
                    }
                }
            }
        } catch (IOException e) {
            log.error("=====读取客户端消息时发生异常{}", e);

        }
    }
}
