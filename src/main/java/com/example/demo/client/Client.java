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
     * 选择器
     */
    private Selector selector;

    private static final String LOGIN = "login";

    /**
     * 连接服务端
     *
     * @param ip       服务端IP
     * @param clientId 客户端ID
     * @param port     服务端端口
     * @throws IOException IO异常
     */
    public void connect(String ip, String clientId, Integer port) throws IOException {
       ByteBuffer buffer = ByteBuffer.allocate(100);
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
        /*
         * 此容器用于保存未完整读取的消息（解决TCP粘包、拆包）
         * key是信道，value是ByteBuffer
         */
        ByteBuffer cacheBuffer = ByteBuffer.allocate(100);
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
                        this.dealBytebuffer(key, (SocketChannel) key.channel(),cacheBuffer);
                    }
                }
            } catch (IOException e) {
                log.error("==============服务端断开了连接~~");
            }
        }
    }

    /**
     * 判断上一次是否出现拆包粘包情况
     *
     * @param key SelectionKey
     * @throws IOException IO异常
     */
    private void dealBytebuffer(SelectionKey key, SocketChannel channel, ByteBuffer cacheBuffer) {
        ByteBuffer buffer = ByteBuffer.allocate(100);
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
                removeCache(cacheBuffer);
                return;
            }
            if (read == -1) {
                key.cancel();
                removeCache(cacheBuffer);
                return;
            }
            // 判断上一次是否发生拆包现象。
            if (cacheBuffer.position()>0) {
                // 如果消息池里有这个key说明发生了拆包或者粘包的现象需要把缓存的Bytebuffer取出
                // 把两个包合在一起
                cacheBuffer.put(buffer);
                cacheBuffer.clear();
                // 读取包数据
                dealMsg(cacheBuffer,cacheBuffer);
            }  else {
                // 如果没有发生拆包粘包现象则正常读取
                dealMsg(buffer, cacheBuffer);
            }
        }

    }

    /**
     * 处理包数据
     *
     * @param buffer 本次要读取的包
     * @param cacheBuffer cacheBuffer 缓存临时包的buffer
     */
    private void dealMsg(ByteBuffer buffer, ByteBuffer cacheBuffer) {
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
        // 如果是私聊消息则消息格式为：包头+客户端ID+消息正文，如果是群聊消息则消息格式为：包头+消息正文
        chatMsgToHandler(firstMsg);
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
            ByteBuffer cacheByteBuffer = ByteBuffer.allocate(100);
            cacheByteBuffer.put(remainMsg.getBytes(StandardCharsets.UTF_8));
            buffer.clear();
            return;
        } else{
            // 先得到剩余包：剩余的包 = 总消息 - 已读包
            // 然后递归调用此方法进行拆包处理
            buffer.clear();
            buffer.put(remainMsg.getBytes(StandardCharsets.UTF_8));
            dealMsg(buffer,cacheBuffer);
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
        ByteBuffer buffer = ByteBuffer.allocate(100);
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

    /**
     * 服务端和客户端断链以后清楚缓存
     */
    private void removeCache(ByteBuffer byteBuffers){
        channels.clear();
        byteBuffers.clear();
    }


}
