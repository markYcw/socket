package com.example.demo.listen;

import com.example.demo.server.Server;
import com.example.demo.swing.ClientMsgReceiver;
import com.example.demo.swing.ServerMsgReceiver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;


/**
 * @describe 服务初始化类：当容器完成初始化后时候初始化一些动作
 * @author mark
 * @date 2022/7/29 15:20
 */
@Slf4j
@Component
public class StartEvent implements CommandLineRunner {

    @Resource
    private ServerMsgReceiver serverMsgReceiver;

    @Resource
    private Server server;

    @Override
    public void run(String... args) throws Exception {
        //服务端初始化
        CompletableFuture.runAsync(() -> {
            try {
                server.startServer();
            } catch (IOException e) {
                log.error("=====启动NIO服务端异常{}", e);
            }
        });
        //服务端信息收集器初始化
        serverMsgReceiver.init();

        //客户端信息收集器初始化
        ClientMsgReceiver receiver = new ClientMsgReceiver();
        ClientMsgReceiver clientMsgReceiver = new ClientMsgReceiver();
        CompletableFuture.runAsync(() -> {
            receiver.init();
            clientMsgReceiver.init();
        });


    }
}
