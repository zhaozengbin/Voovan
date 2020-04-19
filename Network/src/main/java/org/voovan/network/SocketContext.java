package org.voovan.network;

import org.voovan.network.handler.SynchronousHandler;
import org.voovan.network.messagesplitter.TransferSplitter;
import org.voovan.tools.TObject;
import org.voovan.tools.TPerformance;
import org.voovan.tools.collection.Chain;
import org.voovan.tools.buffer.TByteBuffer;
import org.voovan.tools.TEnv;
import org.voovan.tools.event.EventRunner;
import org.voovan.tools.event.EventRunnerGroup;
import org.voovan.tools.log.Logger;
import org.voovan.tools.pool.PooledObject;
import org.voovan.tools.threadpool.ThreadPool;

import javax.net.ssl.SSLException;
import java.io.IOException;
import java.net.SocketOption;
import java.nio.channels.SelectableChannel;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * socket 上下文
 *
 * @author helyho
 *
 * Voovan Framework.
 * WebSite: https://github.com/helyho/Voovan
 * Licence: Apache v2 License
 */
public abstract class SocketContext<C extends SelectableChannel, S extends IoSession> extends PooledObject {
    //================================线程管理===============================
	public static int 		ACCEPT_THREAD_SIZE 	= TEnv.getSystemProperty("AcceptThreadSize", 1);
	public static int 		IO_THREAD_SIZE 		= TEnv.getSystemProperty("IoThreadSize", TPerformance.getProcessorCount()+1);
	public static int 		SELECT_INTERVAL 	= TEnv.getSystemProperty("SelectInterval", 100);
	public static Boolean 	CHECK_READ_TIMEOUT  = TEnv.getSystemProperty("CheckReadTimeout", null);
	public static boolean 	ASYNC_SEND 			= TEnv.getSystemProperty("AsyncSend", true);

	static {
		IO_THREAD_SIZE = IO_THREAD_SIZE < 8 ? 8 : IO_THREAD_SIZE;
	}

	public static EventRunnerGroup COMMON_ACCEPT_EVENT_RUNNER_GROUP;
	public static EventRunnerGroup COMMON_IO_EVENT_RUNNER_GROUP;

	/**
	 * 构造事件管理器
	 * @param name 事件执行器名称
	 * @param size 容纳事件执行器的数量
	 * @param isAccept 是否处理 Accept 事件
	 * @return 事件执行器
	 */
	public static EventRunnerGroup createEventRunnerGroup(String name, int size, boolean isAccept) {
		name = name + "-" + (isAccept ? "Accept" : "IO");
		int threadPriority = isAccept ? 10 : 9;

		return EventRunnerGroup.newInstance(name, size, threadPriority, (obj)->{
			try {
				//IO 线程检查超时
				return new SocketSelector(obj, !isAccept);
			} catch (IOException e) {
				e.printStackTrace();
			}

			return null;
		});
	}

	/**
	 * 获取公共的 Accept 事件执行器
	 * @return Accept 事件执行器
	 */
	public static synchronized EventRunnerGroup getCommonAcceptEventRunnerGroup(){
		if(COMMON_ACCEPT_EVENT_RUNNER_GROUP == null) {
			Logger.simple("[SYSTEM] Create common accept EventRunnerGroup");
			COMMON_ACCEPT_EVENT_RUNNER_GROUP = createEventRunnerGroup("Common", ACCEPT_THREAD_SIZE, true);
		}

		return COMMON_ACCEPT_EVENT_RUNNER_GROUP;
	}

	/**
	 * 获取公共的 IO 事件执行器
	 * @return IO 事件执行器
	 */
	public static synchronized EventRunnerGroup getCommonIoEventRunnerGroup(){
		if(COMMON_IO_EVENT_RUNNER_GROUP == null) {
			Logger.simple("[SYSTEM] Create common IO EventRunnerGroup");
			COMMON_IO_EVENT_RUNNER_GROUP = createEventRunnerGroup("Common", IO_THREAD_SIZE, false);
		}

		return COMMON_IO_EVENT_RUNNER_GROUP;
	}



	static {
		System.out.println("[SOCKET] IO_THREAD_SIZE: " + IO_THREAD_SIZE);
	}

	//===============================SocketChannel=============================
	protected String host;
	protected int port;
	protected int readTimeout;
	protected int sendTimeout = 1000;

	protected IoHandler handler;
	protected Chain<IoFilter> filterChain;
	protected MessageSplitter messageSplitter;
	protected SSLManager sslManager;
	protected ConnectModel connectModel;
	protected int readBufferSize = TByteBuffer.DEFAULT_BYTE_BUFFER_SIZE;
	protected int sendBufferSize = TByteBuffer.DEFAULT_BYTE_BUFFER_SIZE;

	protected int idleInterval = 0;
	protected long lastReadTime = System.currentTimeMillis();

	protected int readRecursionDepth = 1;

	private boolean isRegister = false;
	protected boolean isSynchronous = true;

	private EventRunnerGroup acceptEventRunnerGroup;
	private EventRunnerGroup ioEventRunnerGroup;

	/**
	 * 构造函数
	 * 		默认不会出发空闲事件, 默认发超时时间: 1s
	 * @param host    主机地址
	 * @param port    主机端口
	 * @param readTimeout 超时时间
	 */
	public SocketContext(String host,int port,int readTimeout) {
		init(host, port, readTimeout, sendTimeout, this.idleInterval);
	}

	/**
	 * 构造函数
	 *      默认发超时时间: 1s
	 * @param host    主机地址
	 * @param port    主机端口
	 * @param readTimeout 读超时时间, 单位: 毫秒
	 * @param idleInterval 空闲事件触发时间
	 */
	public SocketContext(String host,int port, int readTimeout, int idleInterval) {
		init(host, port, readTimeout, sendTimeout, idleInterval);
	}

	/**
	 * 构造函数
	 * @param host    主机地址
	 * @param port    主机端口
	 * @param readTimeout 读超时时间, 单位: 毫秒
	 * @param sendTimeout 发超时时间, 单位: 毫秒
	 * @param idleInterval 空闲事件触发时间
	 */
	public SocketContext(String host,int port,int readTimeout, int sendTimeout, int idleInterval) {
		init(host, port, readTimeout, sendTimeout, idleInterval);
	}

	private void init(String host,int port,int readTimeout, int sendTimeout, int idleInterval){
		this.host = host;
		this.port = port;
		this.readTimeout = readTimeout;
		this.sendTimeout = sendTimeout;
		this.idleInterval = idleInterval;
		connectModel = null;
		filterChain = new Chain<IoFilter>();
		this.messageSplitter = new TransferSplitter();
		this.handler = new SynchronousHandler();
	}

	public EventRunnerGroup getAcceptEventRunnerGroup() {
		return acceptEventRunnerGroup;
	}

	public void setAcceptEventRunnerGroup(EventRunnerGroup acceptEventRunnerGroup) {
		this.acceptEventRunnerGroup = acceptEventRunnerGroup;
	}

	public EventRunnerGroup getIoEventRunnerGroup() {
		return ioEventRunnerGroup;
	}

	public void setIoEventRunnerGroup(EventRunnerGroup ioEventRunnerGroup) {
		this.ioEventRunnerGroup = ioEventRunnerGroup;
	}

	protected void initSSL(IoSession session) throws SSLException {
		if (sslManager != null && connectModel == ConnectModel.SERVER) {
			sslManager.createServerSSLParser(session);
		} else if (sslManager != null && connectModel == ConnectModel.CLIENT) {
			sslManager.createClientSSLParser(session);
		}
	}

	/**
	 * 克隆对象
	 * @param parentSocketContext 父 socket 对象
	 */
	protected void copyFrom(SocketContext parentSocketContext){
		this.readTimeout = parentSocketContext.readTimeout;
		this.sendTimeout = parentSocketContext.sendTimeout;
		this.handler = parentSocketContext.handler;
		this.filterChain = parentSocketContext.filterChain;
		this.messageSplitter = parentSocketContext.messageSplitter;
		this.sslManager = parentSocketContext.sslManager;
		this.readBufferSize = parentSocketContext.readBufferSize;
		this.sendBufferSize = parentSocketContext.sendBufferSize;
		this.idleInterval = parentSocketContext.idleInterval;
		this.readRecursionDepth = parentSocketContext.readRecursionDepth;
		this.acceptEventRunnerGroup = parentSocketContext.acceptEventRunnerGroup;
		this.ioEventRunnerGroup = parentSocketContext.ioEventRunnerGroup;
	}

	/**
	 * 获取空闲事件时间
	 * @return  空闲事件时间, 单位:秒
	 */
	public int getIdleInterval() {
		return idleInterval;
	}

	/**
	 * 设置空闲事件时间
	 * @param idleInterval  空闲事件时间, 单位:秒
	 */
	public abstract void setIdleInterval(int idleInterval);

	/**
	 * 设置 Socket 的 Option 选项
	 *
	 * @param name   SocketOption类型的枚举, 参照:AsynchronousSocketChannel.setOption的说明
	 * @param value  SocketOption参数
	 * @param <T> 范型
	 * @throws IOException IO异常
	 */
	public abstract <T> void setOption(SocketOption<T> name, T value) throws IOException;

	/**
	 * 获取 SocketChannel 对象
	 * @return SocketChannel 对象
	 */
	public abstract C socketChannel();

	public long getLastReadTime() {
		return lastReadTime;
	}

	public void updateLastReadTime() {
		this.lastReadTime = System.currentTimeMillis();
	}

	public boolean isReadTimeOut(){
		return (System.currentTimeMillis() - lastReadTime) >= readTimeout;
	}

	/**
	 * 会话读缓冲区大小
	 * @return 读缓冲区大小
	 */
	public int getReadBufferSize() {
		return readBufferSize;
	}

	/**
	 * 设置会话读缓冲区大小
	 * @param readBufferSize 读缓冲区大小
	 */
	public void setReadBufferSize(int readBufferSize) {
		this.readBufferSize = readBufferSize;
	}

	/**
	 * 会话写缓冲区大小
	 * @return 读缓冲区大小
	 */
	public int getSendBufferSize() {
		return sendBufferSize;
	}

	/**
	 * 设置会话写缓冲区大小
	 * @param sendBufferSize 读缓冲区大小
	 */
	public void setSendBufferSize(int sendBufferSize) {
		this.sendBufferSize = sendBufferSize;
	}

	/**
	 * 获取读递归深度控制
	 * @return 读递归深度
	 */
	public int getReadRecursionDepth() {
		return readRecursionDepth;
	}

	/**
	 * 设置读递归深度控制
	 * @param readRecursionDepth 读递归深度
	 */
	public void setReadRecursionDepth(int readRecursionDepth) {
		this.readRecursionDepth = readRecursionDepth;
	}

	public boolean isRegister() {
		return isRegister;
	}

	protected void setRegister(boolean register) {
		isRegister = register;
	}

	/**
	 * 无参数构造函数
	 */
	protected SocketContext() {
	}

	/**
	 * 获取 SSL 管理器
	 * @return SSL 管理器
	 */
	public SSLManager getSSLManager() {
		return sslManager;
	}

	/**
	 * 设置 SSL 管理器
	 * @param sslManager SSL 管理器
	 */
	public void setSSLManager(SSLManager sslManager) {
		if(this.sslManager==null){
			this.sslManager = sslManager;
		}
	}

	/**
	 * 获取主机地址
	 * @return 主机地址
	 */
	public String getHost() {
		return host;
	}

	/**
	 * 获取主机端口
	 * @return 主机端口
	 */
	public int getPort() {
		return port;
	}

	/**
	 * 获取超时时间
	 * @return 超时时间
	 */
	public int getReadTimeout() {
		return readTimeout;
	}

	public int getSendTimeout(){
		return sendTimeout;
	}

	/**
	 * 获取连接模式
	 * @return 连接模式
	 */
	public ConnectModel getConnectModel() {
		return connectModel;
	}

	/**
	 * 获取业务处理句柄
	 * @return 业务处理句柄
	 */
	public IoHandler handler(){
		return this.handler;
	}

	/**
	 * 设置业务处理句柄
	 * @param handler 业务处理句柄
	 */
	public void handler(IoHandler handler){
		this.handler = handler;
		isSynchronous = handler instanceof SynchronousHandler;
	}

	/**
	 * 获取过滤器链
	 * @return 过滤器链
	 */
	public Chain<IoFilter> filterChain(){
		return this.filterChain;
	}

	/**
	 * 获取消息粘包分割器
	 * @return 消息粘包分割器
	 */
	public MessageSplitter messageSplitter() {
		return this.messageSplitter;
	}

	/**
	 * 设置消息粘包分割器
	 * @param  messageSplitter 消息分割器
	 */
	public void messageSplitter(MessageSplitter messageSplitter) {
		this.messageSplitter = messageSplitter;
	}

	public abstract S getSession();

	/**
	 * 启动上下文连接
	 *		阻塞方法
	 * @throws IOException IO 异常
	 */
	public abstract void start() throws IOException;

	/**
	 * 启动同步的上下文连接
	 * 		非阻塞方法
	 *
	 * @exception IOException IO异常
	 */
	public abstract void syncStart() throws IOException;

	/**
	 * 用于针对 Accept 进来的 Socket 连接的启动
	 * @throws IOException IO异常
	 */
	protected abstract void acceptStart() throws IOException;

	/**
	 * 上下文连接是否打开
	 * @return true:连接打开,false:连接关闭
	 */
	public abstract boolean isOpen();


	/**
	 * 上下文连接是否连接
	 * @return true:连接,false:断开
	 */
	public abstract boolean isConnected();

	/**
	 * 关闭连接
	 * @return 是否关闭
	 */
	public abstract boolean close();

	/**
	 * 等待连接完成, 包含事件注册和 SSL 握手, 用于在同步调用的方法中同步
	 */
	public void waitConnect() {
		try {
			//等待注册完成
			TEnv.wait(readTimeout, ()->!isRegister);

			//等待 SSL 握手完成
			if(getSession().isSSLMode()) {
				getSession().getSSLParser().waitHandShakeDone();
			}
		}catch(Exception e){
			Logger.error(e);
			close();
		}
	}

	/**
	 * 绑定到 SocketSelector
	 * @param ops 选择的操作类型
	 */
	public void bindToSocketSelector(int ops) {
		EventRunner eventRunner = null;
		if(connectModel == ConnectModel.LISTENER) {
			if(acceptEventRunnerGroup == null) {
				acceptEventRunnerGroup = getCommonAcceptEventRunnerGroup();
			}
			eventRunner = acceptEventRunnerGroup.choseEventRunner();
		} else {
			if(ioEventRunnerGroup == null) {
				ioEventRunnerGroup = getCommonIoEventRunnerGroup();
			}
			eventRunner = ioEventRunnerGroup.choseEventRunner();
		}
		SocketSelector socketSelector = (SocketSelector)eventRunner.attachment();
		socketSelector.register(this, ops);
	}

    /**
     * 平滑的关闭 Socket 线程池
     */
	public static void gracefulShutdown() {
		if(COMMON_ACCEPT_EVENT_RUNNER_GROUP!=null) {
			ThreadPool.gracefulShutdown(COMMON_ACCEPT_EVENT_RUNNER_GROUP.getThreadPool());
		}
		if(COMMON_IO_EVENT_RUNNER_GROUP!=null) {
			ThreadPool.gracefulShutdown(COMMON_IO_EVENT_RUNNER_GROUP.getThreadPool());
		}
		Logger.info("All IO thread is shutdown");
	}
}
