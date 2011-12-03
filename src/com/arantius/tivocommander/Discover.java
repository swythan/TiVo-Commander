/*
Open Commander for TiVo allows control of a TiVo Premiere device.
Copyright (C) 2011  Anthony Lieuallen (arantius@gmail.com)

This program is free software; you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation; either version 2 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License along
with this program; if not, write to the Free Software Foundation, Inc.,
51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
*/

package com.arantius.tivocommander;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashMap;

import javax.jmdns.JmDNS;
import javax.jmdns.ServiceEvent;
import javax.jmdns.ServiceInfo;
import javax.jmdns.ServiceListener;

import android.app.AlertDialog;
import android.app.ListActivity;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiManager.MulticastLock;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.text.InputType;
import android.view.View;
import android.view.Window;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.EditText;
import android.widget.SimpleAdapter;
import android.widget.TextView;

import com.arantius.tivocommander.rpc.MindRpc;

public class Discover extends ListActivity implements OnItemClickListener,
    ServiceListener {
  private class DvrListUpdater implements Runnable {
    private final HashMap<String, Object> mListItem;
    private final Integer mOldIndex;

    public DvrListUpdater(HashMap<String, Object> listItem, Integer oldIndex) {
      mListItem = listItem;
      mOldIndex = oldIndex;
    }

    public void run() {
      if (mOldIndex != null) {
        Utils.log(String.format("Replace %s with %s", mHosts.get(mOldIndex),
            mListItem));
        mHosts.set(mOldIndex, mListItem);
      } else {
        mHosts.add(mListItem);
      }
      mHostAdapter.notifyDataSetChanged();
    }
  }

  private TextView mEmpty;
  private SimpleAdapter mHostAdapter;
  private volatile ArrayList<HashMap<String, Object>> mHosts =
      new ArrayList<HashMap<String, Object>>();
  private JmDNS mJmdns;
  private MulticastLock mMulticastLock = null;
  private final String mRpcServiceName = "_tivo-mindrpc._tcp.local.";
  private final String[] mServiceNames = new String[] {
      "_tivo-mindrpc._tcp.local.", "_tivo-videos._tcp.local." };

  public final void customSettings(View v) {
    stopQuery();
    Intent intent = new Intent(Discover.this, Settings.class);
    startActivity(intent);
    finish();
  }

  public void onItemClick(AdapterView<?> parent, View view, int position,
      long id) {
    final HashMap<String, Object> item = mHosts.get(position);

    int messageId = (Integer) item.get("messageId");
    if (messageId != 0) {
      showHelp(messageId);
      return;
    }

    final SharedPreferences prefs =
        PreferenceManager.getDefaultSharedPreferences(Discover.this
            .getBaseContext());

    final EditText makEditText = new EditText(Discover.this);
    makEditText.setInputType(InputType.TYPE_CLASS_PHONE);
    makEditText.setText(prefs.getString("tivo_mak", ""));
    new AlertDialog.Builder(Discover.this).setTitle("MAK")
        .setMessage(R.string.pref_mak_instructions).setView(makEditText)
        .setPositiveButton("OK", new DialogInterface.OnClickListener() {
          public void onClick(DialogInterface dialog, int whichButton) {
            Editor editor = prefs.edit();
            editor.putString("tivo_addr", (String) item.get("addr"));
            editor.putString("tivo_port", (String) item.get("port"));
            String mak = makEditText.getText().toString();
            editor.putString("tivo_mak", mak);
            editor.commit();
            Discover.this.finish();
          }
        }).setNegativeButton("Cancel", null).create().show();
  }

  /** ServiceListener */
  public void serviceAdded(ServiceEvent event) {
    // Make sure serviceResolved() gets called.
    event.getDNS().requestServiceInfo(event.getType(), event.getName());
  }

  /** ServiceListener */
  public void serviceRemoved(ServiceEvent event) {
    // Ignore.
  }

  /** ServiceListener */
  public void serviceResolved(ServiceEvent event) {
    ServiceInfo info = event.getInfo();
    Utils.log("Discovery serviceResolved(): " + event.toString());

    String name = event.getName();
    String addr = info.getHostAddresses()[0];
    String port = Integer.toString(info.getPort());
    String tsn = "";
    int messageId = 0;

    final String platform = info.getPropertyString("platform");
    if (platform == null) {
      messageId = R.string.premiere_only;
    } else if (platform.indexOf("Series4") == -1) {
      messageId = R.string.premiere_only;
    } else if (!mRpcServiceName.equals(info.getType())) {
      messageId = R.string.error_net_control;
    } else {
      tsn = info.getPropertyString("TSN");
    }

    Integer oldIndex = null;
    for (HashMap<String, Object> host : mHosts) {
      if (name.equals(host.get("name")) && addr.equals(host.get("addr"))) {
        if ((Integer) host.get("messageId") != 0 && messageId == 0) {
          // If we previously added this as an error (i.e. mindrpc was not the
          // first discovered service) but now we're satisfied: remove the
          // previous item and add this as a replacement.
          oldIndex = mHosts.indexOf(host);
          break;
        } else {
          // Ignore dupes. I'm not sure what Series 2 or 3/HD devices will
          // report, so I listen for everything it might be, and skip dupes
          // here. Also, timing issues are rarely caught here.
          return;
        }
      }
    }

    final HashMap<String, Object> listItem = new HashMap<String, Object>();
    listItem.put("addr", addr);
    listItem.put("messageId", messageId);
    listItem.put("name", name);
    listItem.put("port", port);
    listItem.put("tsn", tsn);
    listItem.put("warn_icon", messageId == 0 ? R.drawable.blank
        : android.R.drawable.ic_dialog_alert);

    runOnUiThread(new DvrListUpdater(listItem, oldIndex));
  }

  public final void showHelp(View V) {
    stopQuery();
    Intent intent = new Intent(Discover.this, Help.class);
    startActivity(intent);
  }

  public final void startQuery(View v) {
    stopQuery();

    mEmpty.setText("Searching ...");

    mHosts.clear();
    mHostAdapter.notifyDataSetChanged();

    WifiManager wifi = (WifiManager) getSystemService(Context.WIFI_SERVICE);
    WifiInfo wifiInfo = wifi.getConnectionInfo();
    int intaddr = wifiInfo.getIpAddress();
    byte[] byteaddr =
        new byte[] { (byte) (intaddr & 0xff), (byte) (intaddr >> 8 & 0xff),
            (byte) (intaddr >> 16 & 0xff), (byte) (intaddr >> 24 & 0xff) };
    InetAddress addr;
    try {
      addr = InetAddress.getByAddress(byteaddr);
    } catch (UnknownHostException e1) {
      showHelp(R.string.error_get_wifi_addr);
      finish();
      return;
    }

    mMulticastLock = wifi.createMulticastLock("Open Commander for TiVo Lock");
    mMulticastLock.setReferenceCounted(true);
    try {
      mMulticastLock.acquire();
    } catch (UnsupportedOperationException e) {
      showHelp(R.string.error_wifi_lock);
      finish();
      return;
    }

    setProgressBarIndeterminateVisibility(true);
    findViewById(R.id.button1).setEnabled(false);

    try {
      mJmdns = JmDNS.create(addr, "localhost");
    } catch (IOException e1) {
      showHelp(R.string.error_multicast);
      finish();
      return;
    }

    for (String serviceName : mServiceNames) {
      mJmdns.addServiceListener(serviceName, this);
    }

    // Don't run for too long.
    new Thread(new Runnable() {
      public void run() {
        try {
          Thread.sleep(7500);
        } catch (InterruptedException e) {
          // Ignore.
        }
        stopQuery();
      }
    }).start();
  }

  private final void showHelp(int messageId) {
    stopQuery();
    String message = getResources().getString(messageId);
    Utils.log("Showing help because:\n" + message);
    Intent intent = new Intent(Discover.this, Help.class);
    intent.putExtra("note", message);
    startActivity(intent);
  }

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    MindRpc.disconnect();

    requestWindowFeature(Window.FEATURE_INDETERMINATE_PROGRESS);
    setContentView(R.layout.list_discover);

    mEmpty = ((TextView) findViewById(android.R.id.empty));
    mHostAdapter =
        new SimpleAdapter(this, mHosts, R.layout.item_discover, new String[] {
            "name", "warn_icon" },
            new int[] { R.id.textView1, R.id.imageView1 });
    setListAdapter(mHostAdapter);

    getListView().setOnItemClickListener(this);
  }

  @Override
  protected void onPause() {
    super.onPause();
    Utils.log("Activity:Pause:Discover");
    stopQuery();
  }

  @Override
  protected void onResume() {
    super.onResume();
    Utils.log("Activity:Resume:Discover");
    startQuery(null);
  }

  protected final void stopQuery() {
    runOnUiThread(new Runnable() {
      public void run() {
        setProgressBarIndeterminateVisibility(false);
        findViewById(R.id.button1).setEnabled(true);
        mEmpty.setText("No results found.");
      }
    });

    // JmDNS close seems to take ~6 seconds, so do that on a background thread.
    if (mJmdns != null) {
      final JmDNS oldMdns = mJmdns;
      mJmdns = null;
      new Thread(new Runnable() {
        public void run() {
          try {
            for (String serviceName : mServiceNames) {
              oldMdns.removeServiceListener(serviceName, Discover.this);
            }
            oldMdns.close();
          } catch (RuntimeException e) {
            Utils.logError("Could not close JmDNS!", e);
          } catch (IOException e) {
            Utils.logError("Could not close JmDNS!", e);
          }
        }
      }).start();
    }

    if (mMulticastLock != null) {
      try {
        mMulticastLock.release();
      } catch (RuntimeException e) {
        // Ignore. Likely "MulticastLock under-locked Open Commander for TiVo Lock".
      }
      mMulticastLock = null;
    }
  }
}
