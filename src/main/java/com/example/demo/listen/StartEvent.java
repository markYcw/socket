package com.example.demo.listen;

import com.example.demo.server.Server;
import com.example.demo.swing.ChatClientUi;
import com.example.demo.swing.ChatServerUi;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;


/**
 * @author mark
 * @describe 服务初始化类：当容器完成初始化后时候初始化一些动作
 * @date 2022/7/29 15:20
 */
@Slf4j
@Component
public class StartEvent implements CommandLineRunner {

    @Resource
    private ChatServerUi chatServerUi;

    @Resource
    private Server server;

    @Override
    public void run(String... args) {
        //服务端初始化
        CompletableFuture.runAsync(() -> {
            try {
                server.startServer();
            } catch (IOException e) {
                log.error("=====启动NIO服务端异常{}", e);
            }
        });
        //服务端信息收集器初始化
        chatServerUi.init();

        //客户端信息收集器初始化
        ChatClientUi receiver = new ChatClientUi();
        ChatClientUi chatClientUi = new ChatClientUi();
        CompletableFuture.runAsync(() -> {
            receiver.init();
            chatClientUi.init();
        });


    }
}
