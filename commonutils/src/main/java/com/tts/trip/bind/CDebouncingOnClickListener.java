package com.tts.trip.bind;

import android.view.View;


public abstract class CDebouncingOnClickListener implements View.OnClickListener {
  private static boolean enabled = true;


  private String name;

  public CDebouncingOnClickListener(String name) {
    this.name = name;
  }

  private static final Runnable ENABLE_AGAIN = new Runnable() {
    @Override public void run() {
      enabled = true;
    }
  };

  @Override public final void onClick(View v) {
    if (enabled) {
      enabled = false;
      v.post(ENABLE_AGAIN);
      doClick(v,name);
    }
  }

  public abstract void doClick(View v,String p1);
}
