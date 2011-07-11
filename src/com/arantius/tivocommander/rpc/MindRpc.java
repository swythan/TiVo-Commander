package com.arantius.tivocommander.rpc;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.net.UnknownHostException;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.Random;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import android.app.Activity;
import android.util.Log;

import com.arantius.tivocommander.Main;
import com.arantius.tivocommander.R;
import com.arantius.tivocommander.rpc.request.BodyAuthenticate;
import com.arantius.tivocommander.rpc.request.MindRpcRequest;
import com.arantius.tivocommander.rpc.response.MindRpcResponse;
import com.arantius.tivocommander.rpc.response.MindRpcResponseListener;

public enum MindRpc {
  INSTANCE;

  private static final String LOG_TAG = "tivo_mindrpc";

  private static volatile int mRpcId = 1;
  private static volatile int mSessionId;

  private static Socket mSocket = null;
  private static BufferedReader mInputStream = null;
  private static MindRpcInput mInputThread = null;
  private static BufferedWriter mOutputStream = null;
  private static MindRpcOutput mOutputThread = null;
  private static HashMap<Integer, MindRpcResponseListener> mResponseListenerMap =
      new HashMap<Integer, MindRpcResponseListener>();

  private static class AlwaysTrustManager implements X509TrustManager {
    public void checkClientTrusted(X509Certificate[] cert, String authType)
        throws CertificateException {
    }

    public void checkServerTrusted(X509Certificate[] cert, String authType)
        throws CertificateException {
    }

    public X509Certificate[] getAcceptedIssuers() {
      return new X509Certificate[0];
    }
  }

  public static int getRpcId() {
    return mRpcId++;
  }

  public static int getSessionId() {
    return mSessionId;
  }

  /**
   * Add an outgoing request to the queue.
   *
   * @param request The requestequest to be sent.
   * @param listener The object to notify when the response(s) come back.
   */
  public static void addRequest(MindRpcRequest request,
      MindRpcResponseListener listener) {
    mOutputThread.addRequest(request);
    if (listener != null) {
      mResponseListenerMap.put(request.getRpcId(), listener);
    }
  }

  private static boolean connect() {
    Log.i(LOG_TAG, ">>> connect() ...");

    SSLSocketFactory sslSocketFactory = null;

    // Set up the socket factory.
    try {
      TrustManager[] tm = new TrustManager[] { new AlwaysTrustManager() };
      SSLContext context = SSLContext.getInstance("TLS");
      context.init(new KeyManager[0], tm, new SecureRandom());

      sslSocketFactory = context.getSocketFactory();
    } catch (KeyManagementException e) {
      Log.e(LOG_TAG, "ssl: KeyManagementException!", e);
      return false;
    } catch (NoSuchAlgorithmException e) {
      Log.e(LOG_TAG, "ssl: NoSuchAlgorithmException!", e);
      return false;
    }

    // And use it to create a socket.
    try {
      mSessionId = 0x26c000 + new Random().nextInt(0xFFFF);
      mSocket = sslSocketFactory.createSocket(Main.mTivoAddr, Main.mTivoPort);
      mInputStream =
          new BufferedReader(new InputStreamReader(mSocket.getInputStream()));
      mOutputStream =
          new BufferedWriter(new OutputStreamWriter(mSocket.getOutputStream()));
    } catch (UnknownHostException e) {
      Log.i(LOG_TAG, "connect: unknown host!", e);
      return false;
    } catch (IOException e) {
      Log.e(LOG_TAG, "connect: io exception!", e);
      return false;
    }

    return true;
  }

  private static void disconnect() {
    if (mInputStream != null) {
      try {
        mInputStream.close();
      } catch (IOException e) {
        Log.e(LOG_TAG, "disconnect()", e);
      }
    }
    if (mOutputStream != null) {
      try {
        mOutputStream.close();
      } catch (IOException e) {
        Log.e(LOG_TAG, "disconnect()", e);
      }
    }
    if (mSocket != null) {
      try {
        mSocket.close();
      } catch (IOException e) {
        Log.e(LOG_TAG, "disconnect()", e);
      }
    }
  }

  protected static void dispatchResponse(MindRpcResponse response) {
    Integer rpcId = response.getRpcId();
    if (!mResponseListenerMap.containsKey(rpcId)) {
      return;
    }

    mResponseListenerMap.get(rpcId).onResponse(response);
    // TODO: Remove only when the response .isFinal().
    mResponseListenerMap.remove(rpcId);
  }

  public static int init(Activity originActivity) {
    Log.i(LOG_TAG, ">>> init() ...");

    stopThreads();
    disconnect();
    if (!connect()) {
      return R.string.error_connect;
    }

    mInputThread = new MindRpcInput(mInputStream);
    mInputThread.start();

    mOutputThread = new MindRpcOutput(mOutputStream);
    mOutputThread.start();

    addRequest(new BodyAuthenticate(), new MindRpcResponseListener() {
      public void onResponse(MindRpcResponse response) {
        Log.d(LOG_TAG, "Listener for bodyauth ran!");
      }
    });

    return 0;
  }

  private static void stopThreads() {
    if (mInputThread != null) {
      mInputThread.mStopFlag = true;
      mInputThread.interrupt();
      mInputThread = null;
    }
    if (mOutputThread != null) {
      mOutputThread.mStopFlag = true;
      mOutputThread.interrupt();
      mOutputThread = null;
    }
  }
}