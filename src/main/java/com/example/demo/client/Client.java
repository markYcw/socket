package com.example.demo.client;

import com.example.demo.swing.MsgHandler;
import com.example.demo.utils.ContextUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.ObjectUtils;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author mark
 * @describe socket通信客户端
 * @date 2022/8/2 11:12
 */
@Slf4j
@Component
public class Client {

    /**
     * key是客户端id，value是信道
     */
    private final ConcurrentHashMap<String, SocketChannel> channels = new ConcurrentHashMap<>();

    /**
     * ByteBuffer客户端读取服务端发来的信息或者是写入信息给服务端都通过这个字节缓冲区
     */
    private final ByteBuffer buffer = ByteBuffer.allocate(100);

    /**
     * 此容器用于保存未完整读取的消息（解决TCP粘包、拆包）
     * key是信道，value是已经读取的消息，但此容器还要配合remainMsgLength这个属性使用
     */
    private final ConcurrentHashMap<SocketChannel, String> readMsgPool = new ConcurrentHashMap<>();

    /**
     * 此容器用于保存未完整读取的消息（解决TCP粘包、拆包）
     * key是信道，value是已经读取的消息的长度的第一位
     */
    private final ConcurrentHashMap<SocketChannel, String> halfMsgLength = new ConcurrentHashMap<>();

    /**
     * 选择器
     */
    private Selector selector;

    private static final String LOGIN = "login";

    /**
     * 此变量用于记录还需要读取的长度
     */
    private Integer remainMsgLength = 0;

    /**
     * 连接服务端
     *
     * @param ip       服务端IP
     * @param clientId 客户端ID
     * @param port     服务端端口
     * @throws IOException IO异常
     */
    public void connect(String ip, String clientId, Integer port) throws IOException {

        // 得到一个网络通道
        SocketChannel socketChannel = SocketChannel.open();

        // 设置为非阻塞
        socketChannel.configureBlocking(false);

        // 根据服务端的ip和端口
        InetSocketAddress inetSocketAddress = new InetSocketAddress(ip, port);

        // 连接服务器
        socketChannel.connect(inetSocketAddress);

        // 如果连接成功，就发送数据
        if (socketChannel.finishConnect()) {
            channels.put(clientId, socketChannel);
            buffer.clear();
            // 给服务端发送登录消息，消息格式：login+clientId
            String loginMsg = LOGIN + clientId;
            // 添加包头
            loginMsg = addPacketLength(loginMsg);
            buffer.put(loginMsg.getBytes(StandardCharsets.UTF_8));
            buffer.flip();
            // 发送数据，将buffer数据写入channel
            socketChannel.write(buffer);
            CompletableFuture.runAsync(() -> listenReadable(socketChannel));
        }
    }

    /**
     * 监听可读事件
     *
     * @param socketChannel 信道
     * @throws IOException IO异常
     */
    private void listenReadable(SocketChannel socketChannel) {
        // 打开多路复用器
        try {
            selector = Selector.open();
            socketChannel.register(selector, SelectionKey.OP_READ);
            readMsg();
        } catch (IOException e) {
            log.error("=======监听客户端可读事件异常{}", e);
        }
    }

    /**
     * 多路复用部分。当select检测到有可读事件则读取服务端所发来的信息,并做相应处理
     */
    private void readMsg() {
        while (true) {
            try {
                selector.select();
                // 获取注册在selector上的所有的就绪状态的serverSocketChannel中发生的事件
                Iterator<SelectionKey> keys = selector.selectedKeys().iterator();
                while (keys.hasNext()) {
                    SelectionKey key = keys.next();
                    keys.remove();
                    // 6 验证操作 判断管道是否有效 true 有效，false 无效
                    if (key.isValid()) {
                        /*
                         * 管道状态
                         * SelectionKey.OP_CONNECT 是否连接
                         * SelectionKey.OP_ACCEPT  是否阻塞
                         * SelectionKey.OP_READ    是否可读
                         *  SelectionKey.OP_WRITE  是否可写
                         */
                        this.handleServerInput(key, (SocketChannel) key.channel());
                    }
                }
            } catch (IOException e) {
                log.error("==============服务端断开了连接~~");
            }
        }
    }

    /**
     * 处理服务端发来数据
     *
     * @param key SelectionKey
     * @throws IOException IO异常
     */
    private synchronized void handleServerInput(SelectionKey key, SocketChannel channel) {
        SocketChannel sc = (SocketChannel) key.channel();
        //如果客户端接收到了服务器端发送的应答消息 则SocketChannel是可读的
        if (key.isReadable()) {
            // 读取服务端数据
            buffer.clear();
            int read = 0;
            try {
                read = sc.read(buffer);
            } catch (IOException e) {
                log.error("======服务端断开了连接~~~~");
                key.cancel();
            }
            if (read == -1) {
                key.cancel();
            }
            buffer.flip();
            byte[] readByte = new byte[buffer.remaining()];
            buffer.get(readByte);
            String s = new String(readByte);
            Integer readableLength = readByte.length;
            // 判断此次消息的头两位是否是包头。这时分三种情况：第一种就是这是这次没有发生粘包拆包现象这种直接读取就好了。第二种就是上一次发生粘包现象。
            // 第三种情况是上一次发生拆包现象。
            if (readMsgPool.containsKey(channel)) {
                // 如果消息池里有这个key说明发生了拆包或者粘包的现象需要把消息池里消息取出
                // 这种情况是上一次读取了部分内容
                String readMsg = readMsgPool.get(channel);
                readRemainMsg(readableLength, s, readMsg, channel);
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
                readRemainMsg(readableLength - 1, msg, "", channel);
            } else {
                // 如果没有发生拆包粘包现象则正常读取
                remainMsgLength = Integer.valueOf(s.substring(0, 2));
                readRemainMsg(readableLength - 2, s.substring(2), "", channel);
            }
            buffer.clear();
        }

    }

    /**
     * 上一次发生粘包，这一次读取剩余内容消息
     *
     * @param readableLength 本次要读取的可读字节长度
     * @param msg            本次要读取的消息正文
     * @param readMsg        上一次读取的消息内容
     * @param channel        信道
     */
    private void readRemainMsg(Integer readableLength, String msg, String readMsg, SocketChannel channel) {
        // 截取上一次剩余消息
        String remainMsg = null;
        try {
            remainMsg = msg.substring(0, remainMsgLength);
        } catch (Exception e) {
            e.printStackTrace();
        }
        String totalMsg = readMsg + remainMsg;
        // 如果是私聊消息则消息格式为：包头+客户端ID+消息正文，如果是群聊消息则消息格式为：包头+消息正文
        chatMsgToHandler(totalMsg);
        Integer remain = readableLength - remainMsgLength;
        // 判断本次是否还有消息可读,如无可读消息则退出
        if (remain == 0) {
            return;
        }
        // 如果除去已读内容剩余可读内容只剩一个字节，那么这个字节内容肯定是下一个消息包头的第一个字节
        if (remain == 1) {
            // 记录本次读到的包头第一位内容
            halfMsgLength.put(channel, msg.substring(readableLength - 1));
        }
        // 读取剩余消息包头
        Integer newMsgLength = Integer.valueOf(msg.substring(remainMsgLength, remainMsgLength + 2));
        // 剩余可读字节数
        Integer remainReadable = readableLength - remainMsgLength - 2;
        if (remainReadable == newMsgLength) {
            // 如果新消息长度等于剩余可读消息长度则直接读
            chatMsgToHandler(msg.substring(remainMsgLength + 2));
        } else if (newMsgLength < remainReadable) {
            // 如果新消息长度小于剩余可读长度说明又发生了粘包现象
            dealPacketSplicing(remainReadable, newMsgLength, readableLength, msg, channel);
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
     * 处理粘包问题
     *
     * @param remainReadable 剩余可读字节数
     * @param newMsgLength   新消息字节数
     * @param readableLength 本次要读取的可读字节长度
     * @param msg            本次要读取的消息正文
     * @param channel        信道
     */
    private void dealPacketSplicing(Integer remainReadable, Integer newMsgLength, Integer readableLength, String msg, SocketChannel channel) {
        // 这时候又分三种情况:第一种情况是剩余未读消息里只包含一位下一个新消息的包头的第一位数字
        // 第二种情况是剩余未读消息里包含了下一次新消息包头信息
        // 第三种情况是剩余未读消息包含了下一次新消息包头信息和部分消息体
        if (remainReadable - newMsgLength == 1) {
            // 第一种情况
            // 下一个消息
            String s = msg.substring(remainMsgLength + 2, readableLength - 1);
            chatMsgToHandler(s);
            // 把下一次新消息的头部第一个字节放入容器
            halfMsgLength.put(channel, msg.substring(readableLength - 1));
        } else if (remainReadable - newMsgLength == 2) {
            // 第二种情况
            // 下一个消息
            String s = msg.substring(remainMsgLength + 2, readableLength - 2);
            chatMsgToHandler(s);
            // 记录剩余未读字节数
            remainMsgLength = Integer.valueOf(msg.substring(readableLength - 2));
            readMsgPool.put(channel, "");
        } else {
            // 第三种情况剩余未读消息包含了下一次新消息包头信息和部分消息体
            // 下一个消息
            String s = msg.substring(remainMsgLength + 2, newMsgLength);
            chatMsgToHandler(s);
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
    }

    /**
     * 把消息转发给msgHandler进行下一步处理
     *
     * @param message 聊天消息
     */
    private void chatMsgToHandler(String message) {
        try {
            ContextUtils.getBean(MsgHandler.class).serverMsgToClientUi(message);
        } catch (InterruptedException e) {
            log.error("=====客户端接收到服务端消息后将消息转发给MsgHandler出现异常{}", e);
        }
    }

    /**
     * 给服务端发送消息
     *
     * @param clientId 客户端id
     * @param msg      要给服务端发送的信息
     * @throws IOException
     */
    public synchronized void sendMsgToServer(String clientId, String msg) throws IOException {
        //添加包头
        String message = addPacketLength(msg);
        SocketChannel socketChannel = channels.get(clientId);
        if (!ObjectUtils.isEmpty(socketChannel)) {
            buffer.clear();
            buffer.put(message.getBytes(StandardCharsets.UTF_8));
            buffer.flip();
            socketChannel.write(buffer);
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
