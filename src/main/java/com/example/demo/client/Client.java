package com.example.demo.client;

import com.example.demo.swing.MsgHandler;
import com.example.demo.utils.ContextUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
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
    private ConcurrentHashMap<String, SocketChannel> channels = new ConcurrentHashMap<>();

    /**
     * ByteBuffer客户端读取服务端发来的信息或者是写入信息给服务端都通过这个字节缓冲区
     */
    private final ByteBuffer buffer = ByteBuffer.allocate(100);

    /**
     * 此容器用于保存客户端给服务端发超过50个字符的消息
     * key是消息长度，value是消息
     */
    private ConcurrentHashMap<Integer, String> msgPool = new ConcurrentHashMap<>();

    /**
     * 选择器
     */
    private Selector selector;

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
        if (!socketChannel.connect(inetSocketAddress)) {

            while (!socketChannel.finishConnect()) {
                log.info("正在连接中，请稍后...");
            }
        }

        // 如果连接成功，就发送数据
        channels.put(clientId, socketChannel);
        buffer.clear();
        // 给服务端发送登录消息，消息格式：login+clientId
        String str = "login" + clientId;
        buffer.put(str.getBytes(StandardCharsets.UTF_8));
        buffer.flip();
        // 发送数据，将buffer数据写入channel
        socketChannel.write(buffer);
        register(socketChannel);
    }

    /**
     * 注册感兴趣事件到selector
     *
     * @param socketChannel 信道
     * @throws IOException IO异常
     */
    private void register(SocketChannel socketChannel) throws IOException {
        // 打开多路复用器
        selector = Selector.open();
        socketChannel.register(selector, SelectionKey.OP_READ);
        readMsg();
    }

    /**
     * 多路复用部分。当select检测到有可读事件则读取服务端所发来的信息
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
                        /**
                         * 管道状态
                         * SelectionKey.OP_CONNECT 是否连接
                         * SelectionKey.OP_ACCEPT  是否阻塞
                         * SelectionKey.OP_READ    是否可读
                         *  SelectionKey.OP_WRITE  是否可写
                         */
                        this.handleServerInput(key);
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
                System.exit(1);
            }
        }
    }

    /**
     * 处理服务端发来数据
     *
     * @param key SelectionKey
     * @throws IOException IO异常
     */
    private void handleServerInput(SelectionKey key) throws IOException {

        SocketChannel sc = (SocketChannel) key.channel();
        // 处于连接状态
        if (key.isConnectable()) {
            // 客户端连接成功
            if (sc.finishConnect()) {
                // 注册到selector为 可读状态
                sc.register(selector, SelectionKey.OP_READ);
                byte[] requestBytes = "客户端发送数据.".getBytes();
                ByteBuffer bf = ByteBuffer.allocate(requestBytes.length);
                bf.put(requestBytes);
                // 缓冲区复位
                bf.flip();
                // 发送数据
                sc.write(bf);
            }
        }

        //如果客户端接收到了服务器端发送的应答消息 则SocketChannel是可读的
        if (key.isReadable()) {
            // 读取服务端数据
            buffer.clear();
            int read = sc.read(buffer);
            byte[] readByte = new byte[read];
            buffer.flip();
            buffer.get(readByte);
            String s = new String(readByte);
            // 判断消息长度判断是否大于50个字符，如果小于50则立即给客户端UI进行返显，否则需要等消息全部收完再进行返显
            Integer msgLength = Integer.valueOf(s.substring(0, 2));
            try {
                if (msgLength > 50) {
                    String str = msgPool.get(msgLength);
                    if (StringUtils.isEmpty(str)) {
                        msgPool.put(msgLength, s.substring(2));
                    } else {
                        String totalMsg = str + s.substring(4);
                        ContextUtils.getBean(MsgHandler.class).serverMsgToClientUi(totalMsg);
                        //消费完消息去除消息池里面内容
                        msgPool.remove(msgLength);
                    }
                } else {
                    //如果小于50个字符则直接进行返显
                    ContextUtils.getBean(MsgHandler.class).serverMsgToClientUi(s.substring(2));
                }
            } catch (InterruptedException e) {
                log.error("=====客户端接收到服务端消息后将消息转发给MsgHandler出现异常{}", e);
            }
            // 重新注册写事件
            key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
        }

    }

    /**
     * 给服务端发送消息
     *
     * @param clientId 客户端id
     * @param msg      要给服务端发送的信息
     * @throws IOException
     */
    public void sendMsgToServer(String clientId, String msg) throws IOException {
        SocketChannel socketChannel = channels.get(clientId);
        if (!ObjectUtils.isEmpty(socketChannel)) {
            buffer.clear();
            buffer.put(msg.getBytes(StandardCharsets.UTF_8));
            buffer.flip();
            socketChannel.write(buffer);
        }
    }


}
