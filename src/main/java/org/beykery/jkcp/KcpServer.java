/**
 * kcp服务器
 */
package org.beykery.jkcp;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.nio.NioDatagramChannel;
import java.net.InetSocketAddress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author beykery
 */
public abstract class KcpServer implements Output, KcpListerner
{

  private static final Logger LOG = LoggerFactory.getLogger(KcpServer.class);
  private final NioDatagramChannel channel;
  private final InetSocketAddress addr;
  private int nodelay;
  private int interval = Kcp.IKCP_INTERVAL;
  private int resend;
  private int nc;
  private int sndwnd = Kcp.IKCP_WND_SND;
  private int rcvwnd = Kcp.IKCP_WND_RCV;
  private int mtu = Kcp.IKCP_MTU_DEF;
  private KcpThread[] workers;

  /**
   * server
   *
   * @param port
   * @param workerSize
   */
  public KcpServer(int port, int workerSize)
  {
    if (port <= 0 || workerSize <= 0)
    {
      throw new IllegalArgumentException("参数非法");
    }
    final NioEventLoopGroup nioEventLoopGroup = new NioEventLoopGroup();
    Bootstrap bootstrap = new Bootstrap();
    bootstrap.channel(NioDatagramChannel.class);
    bootstrap.group(nioEventLoopGroup);
    bootstrap.handler(new ChannelInitializer<NioDatagramChannel>()
    {

      @Override
      protected void initChannel(NioDatagramChannel ch) throws Exception
      {
        ChannelPipeline cp = ch.pipeline();
        cp.addLast(new KcpServer.UdpHandler());
      }
    });
    this.workers = new KcpThread[workerSize];
    for (int i = 0; i < workerSize; i++)
    {
      workers[i] = new KcpThread(this, this);
      workers[i].wndSize(sndwnd, rcvwnd);
      workers[i].noDelay(nodelay, interval, resend, nc);
      workers[i].setMtu(mtu);
      workers[i].start();
    }
    ChannelFuture sync = bootstrap.bind(port).syncUninterruptibly();
    channel = (NioDatagramChannel) sync.channel();
    addr = channel.localAddress();
    Runtime.getRuntime().addShutdownHook(new Thread(new Runnable()
    {
      @Override
      public void run()
      {
        nioEventLoopGroup.shutdownGracefully();
      }
    }));
  }

  /**
   * close
   *
   * @return
   */
  public ChannelFuture close()
  {
    return this.channel.close();
  }

  /**
   * kcp call
   *
   * @param msg
   * @param kcp
   * @param user
   */
  @Override
  public void out(ByteBuf msg, Kcp kcp, Object user)
  {
    DatagramPacket temp = new DatagramPacket(msg, (InetSocketAddress) user, this.addr);
    this.channel.writeAndFlush(temp);
  }

  /**
   * fastest: ikcp_nodelay(kcp, 1, 20, 2, 1) nodelay: 0:disable(default),
   * 1:enable interval: internal update timer interval in millisec, default is
   * 100ms resend: 0:disable fast resend(default), 1:enable fast resend nc:
   * 0:normal congestion control(default), 1:disable congestion control
   *
   * @param nodelay
   * @param interval
   * @param resend
   * @param nc
   */
  public void noDelay(int nodelay, int interval, int resend, int nc)
  {
    this.nodelay = nodelay;
    this.interval = interval;
    this.resend = resend;
    this.nc = nc;
  }

  /**
   * set maximum window size: sndwnd=32, rcvwnd=32 by default
   *
   * @param sndwnd
   * @param rcvwnd
   */
  public void wndSize(int sndwnd, int rcvwnd)
  {
    this.sndwnd = sndwnd;
    this.rcvwnd = rcvwnd;
  }

  /**
   * change MTU size, default is 1400
   *
   * @param mtu
   */
  public void setMtu(int mtu)
  {
    this.mtu = mtu;
  }

  /**
   * 发送
   *
   * @param bb
   * @param ku
   */
  public void send(ByteBuf bb, KcpOnUdp ku)
  {
    ku.send(bb);
  }

  /**
   * receive DatagramPacket
   *
   * @param dp
   */
  private void onReceive(DatagramPacket dp)
  {
    InetSocketAddress sender = dp.sender();
    int hash = sender.hashCode();
    this.workers[hash % workers.length].input(dp);
  }

  /**
   * handler
   */
  class UdpHandler extends ChannelInboundHandlerAdapter
  {

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception
    {
      DatagramPacket dp = (DatagramPacket) msg;
      KcpServer.this.onReceive(dp);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception
    {
      KcpServer.this.handleException(cause);
    }
  }
}
