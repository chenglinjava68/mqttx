package com.jun.mqttx.server.handler;

import com.jun.mqttx.common.config.BizConfig;
import com.jun.mqttx.entity.Session;
import com.jun.mqttx.server.BrokerHandler;
import com.jun.mqttx.service.IAuthenticationService;
import com.jun.mqttx.service.IPublishMessageService;
import com.jun.mqttx.service.ISessionService;
import com.jun.mqttx.service.ISubscriptionService;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelId;
import io.netty.channel.ChannelOutboundInvoker;
import io.netty.handler.codec.mqtt.*;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.AttributeKey;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static io.netty.handler.codec.mqtt.MqttMessageType.CONNECT;

/**
 * {@link io.netty.handler.codec.mqtt.MqttMessageType#CONNECT} 消息处理器
 *
 * @author Jun
 * @date 2020-03-03 22:17
 */
@Component
public final class ConnectHandler extends AbstractMqttMessageHandler {

    private static final String NONE_ID_PREFIX = "NONE_";

    /**
     * 初始化10000长连接客户端
     */
    public final static ConcurrentHashMap<String, ChannelId> clientMap = new ConcurrentHashMap<>(10000);

    /**
     * 认证服务
     */
    private IAuthenticationService authenticationService;

    /**
     * 会话服务
     */
    private ISessionService sessionService;

    /**
     * 主题订阅相关服务
     */
    private ISubscriptionService subscriptionService;

    /**
     * publish 消息服务
     */
    private IPublishMessageService publishMessageService;

    public ConnectHandler(IAuthenticationService authenticationService, ISessionService sessionService, StringRedisTemplate
            stringRedisTemplate, BizConfig bizConfig, ISubscriptionService subscriptionService,IPublishMessageService publishMessageService) {
        super(stringRedisTemplate, bizConfig);
        Assert.notNull(authenticationService, "authentication can't be null");
        Assert.notNull(sessionService, "sessionService can't be null");
        Assert.notNull(subscriptionService, "subscriptionService can't be null");
        Assert.notNull(publishMessageService,"publishMessageService can't be null");

        this.authenticationService = authenticationService;
        this.sessionService = sessionService;
        this.subscriptionService = subscriptionService;
        this.publishMessageService = publishMessageService;
    }

    /**
     * 客户端连接请求处理，流程如下：
     * <ol>
     *     <li>连接校验</li>
     *     <li>处理 clientId 关联的连接 </li>
     *     <li>返回响应报文</li>
     *     <li>心跳检测</li>
     *     <li>Qos1\Qos2 消息处理</li>
     * </ol>
     *
     * @param ctx 见 {@link ChannelHandlerContext}
     * @param msg 解包后的数据
     */
    @Override
    public void process(ChannelHandlerContext ctx, MqttMessage msg) {
        //获取identifier,password
        MqttConnectMessage mcm = (MqttConnectMessage) msg;
        MqttConnectVariableHeader variableHeader = mcm.variableHeader();
        MqttConnectPayload payload = mcm.payload();

        //用户名及密码校验
        if (variableHeader.hasPassword() && variableHeader.hasUserName()) {
            authenticationService.authenticate(payload.userName(), payload.passwordInBytes());
        }

        //获取clientId
        String clientId = mcm.payload().clientIdentifier();
        if (StringUtils.isEmpty(clientId)) {
            //[MQTT-3.1.3-8] If the Client supplies a zero-byte ClientId with CleanSession set to 0, the Server MUST
            // respond to the CONNECT Packet with a CONNACK return code 0x02 (Identifier rejected) and then close the
            // Network Connection.
            if (!variableHeader.isCleanSession()) {
                throw new MqttIdentifierRejectedException("Violation: zero-byte ClientId with CleanSession set to 0");
            }

            //broker  生成一个唯一ID
            //[MQTT-3.1.3-6] A Server MAY allow a Client to supply a ClientId that has a length of zero bytes,
            //however if it does so the Server MUST treat this as a special case and assign a unique ClientId to that Client.
            //It MUST then process the CONNECT packet as if the Client had provided that unique ClientId
            clientId = genClientId();
        }

        //关闭之前可能存在的tcp链接
        //[MQTT-3.1.4-2] If the ClientId represents a Client already connected to the Server then the Server MUST
        //disconnect the existing Client
        Optional.ofNullable(clientMap.get(clientId))
                .map(BrokerHandler.channels::find)
                .ifPresent(ChannelOutboundInvoker::close);

        //会话状态的处理
        //[MQTT-3.1.3-7] If the Client supplies a zero-byte ClientId, the Client MUST also set CleanSession to 1. -
        //这部分是 client 遵守的规则
        //[MQTT-3.1.2-6] State data associated with this Session MUST NOT be reused in any subsequent Session - 针对
        //clearSession == 1 的情况，需要清理之前保存的会话状态
        boolean clearSession = variableHeader.isCleanSession();
        if (clearSession) {
            actionOnCleanSession(clientId);
        }

        //新建会话并保存会话状态
        Session session = new Session();
        session.setClientId(clientId);
        ctx.channel().attr(AttributeKey.valueOf("clientId")).set(clientId);
        sessionService.save(session);

        //处理遗嘱消息
        //[MQTT-3.1.2-8] If the Will Flag is set to 1 this indicates that, if the Connect request is accepted, a Will
        // Message MUST be stored on the Server and associated with the Network Connection. The Will Message MUST be
        // published when the Network Connection is subsequently closed unless the Will Message has been deleted by the
        // Server on receipt of a DISCONNECT Packet.
        boolean willFlag = variableHeader.isWillFlag();
        if (willFlag) {
            MqttPublishMessage mqttPublishMessage = MqttMessageBuilders.publish()
                    .messageId(nextMessageId(clientId))
                    .retained(variableHeader.isWillRetain())
                    .topicName(payload.willTopic())
                    .payload(Unpooled.buffer().writeBytes(payload.willMessageInBytes()))
                    .qos(MqttQoS.valueOf(variableHeader.willQos()))
                    .build();
            session.setWillMessage(mqttPublishMessage);
        }

        //返回连接响应
        //[MQTT-3.2.0-1] The first packet sent from the Server to the Client MUST be a CONNACK Packet.
        boolean sessionPresent;
        if (clearSession) {
            sessionPresent = false;
        } else {
            sessionPresent = sessionService.hasKey(clientId);
        }
        MqttConnAckMessage acceptAck = MqttMessageBuilders.connAck()
                .sessionPresent(sessionPresent)
                .returnCode(MqttConnectReturnCode.CONNECTION_ACCEPTED)
                .build();
        ctx.writeAndFlush(acceptAck);

        //心跳超时设置
        //[MQTT-3.1.2-24] If the Keep Alive value is non-zero and the Server does not receive a Control Packet from
        //the Client within one and a half times the Keep Alive time period, it MUST disconnect the Network Connection
        //to the Client as if the network had failed
        double heartbeat = variableHeader.keepAliveTimeSeconds() * 1.5;
        if (heartbeat > 0) {
            //替换掉 NioChannelSocket 初始化时加入的 idleHandler
            ctx.pipeline().replace(IdleStateHandler.class, "idleHandler", new IdleStateHandler(
                    0, 0, (int) heartbeat));
        }

        //todo 当cleanSession=0时，我们应该补发该客户未能送达的消息
        if (!clearSession) {

        }
    }

    @Override
    public MqttMessageType handleType() {
        return CONNECT;
    }

    /**
     * 生成一个唯一ID
     *
     * @return Unique Id
     */
    private String genClientId() {
        return NONE_ID_PREFIX + System.currentTimeMillis();
    }

    /**
     * 当 conn cleanSession = 1,清理会话状态.会话状态有如下状态组成:
     * <ul>
     *     <li>The existence of a Session, even if the rest of the Session state is empty.</li>
     *     <li>The Client’s subscriptions.</li>
     *     <li>QoS 1 and QoS 2 messages which have been sent to the Client, but have not been completely acknowledged.</li>
     *     <li>QoS 1 and QoS 2 messages pending transmission to the Client.</li>
     *     <li>QoS 2 messages which have been received from the Client, but have not been completely acknowledged.</li>
     *     <li>Optionally, QoS 0 messages pending transmission to the Client.</li>
     * </ul>
     *
     * @param clientId 客户ID
     */
    private void actionOnCleanSession(String clientId) {
        sessionService.clear(clientId);
        subscriptionService.clearClientSubscriptions(clientId);
        publishMessageService.clear(clientId);
    }
}