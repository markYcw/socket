package com.example.demo.client;

import com.example.demo.swing.MsgHandler;
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
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author mark
 * @date 2022/8/2 11:12
 * @describe socket通信客户端
 */
@Slf4j
@Component
public class Client {

    /**
     * key是客户端id，value是信道
     */
    private ConcurrentHashMap<String, SocketChannel> channels = new ConcurrentHashMap<>();

    private final ByteBuffer buffer = ByteBuffer.allocate(100);

    private Selector selector;

    public void connect(String ip, String clientId, Integer port) throws IOException {

        //得到一个网络通道
        SocketChannel socketChannel = SocketChannel.open();

        //设置为非阻塞
        socketChannel.configureBlocking(false);

        //根据服务端的ip和端口
        InetSocketAddress inetSocketAddress = new InetSocketAddress(ip, port);

        //连接服务器
        if (!socketChannel.connect(inetSocketAddress)) {

            while (!socketChannel.finishConnect()) {
                log.info("正在连接中，请稍后...");
            }
        }

        //如果连接成功，就发送数据
        channels.put(clientId, socketChannel);
        buffer.clear();
        //给服务端发送登录消息，消息格式：login+clientId
        String str = "login" + clientId;
        buffer.put(str.getBytes(StandardCharsets.UTF_8));
        buffer.flip();
        //发送数据，将buffer数据写入channel
        socketChannel.write(buffer);
        register(socketChannel);
    }

    public void register(SocketChannel socketChannel) throws IOException {
        // 打开多路复用器
        selector = Selector.open();
        socketChannel.register(selector, SelectionKey.OP_READ);
        readMsg();
    }

    public void readMsg() {
        while (true) {
            try {
                selector.select();
                //获取注册在selector上的所有的就绪状态的serverSocketChannel中发生的事件
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
                        this.handleInput(key);
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
                System.exit(1);
            }
        }
    }

    /**
     * 处理数据
     */
    private void handleInput(SelectionKey key) throws IOException {

        SocketChannel sc = (SocketChannel) key.channel();
        if (key.isConnectable()) { //处于连接状态
            if (sc.finishConnect()) {//客户端连接成功
                //注册到selector为 可读状态
                sc.register(selector, SelectionKey.OP_READ);
                byte[] requestBytes = "客户端发送数据.".getBytes();
                ByteBuffer bf = ByteBuffer.allocate(requestBytes.length);
                bf.put(requestBytes);
                //缓冲区复位
                bf.flip();
                //发送数据
                sc.write(bf);
            }
        }

        if (key.isReadable()) {//如果客户端接收到了服务器端发送的应答消息 则SocketChannel是可读的
            // 读取服务端数据
            buffer.clear();
            int read = sc.read(buffer);
            byte[] readByte = new byte[read];
            buffer.flip();
            buffer.get(readByte);
            String s = new String(readByte);
            try {
                MsgHandler.getInstance().clientServerMsg(s);
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
    public void sendMsg(String clientId, String msg) throws IOException {
        SocketChannel socketChannel = channels.get(clientId);
        if (!ObjectUtils.isEmpty(socketChannel)) {
            buffer.clear();
            buffer.put(msg.getBytes(StandardCharsets.UTF_8));
            buffer.flip();
            socketChannel.write(buffer);
        }
    }


}