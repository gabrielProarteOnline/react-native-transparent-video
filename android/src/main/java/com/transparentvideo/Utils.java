package com.transparentvideo;

import android.content.Context;

public class Utils {
  public static int getRawResourceId(Context context, String resourceName) {
    return context.getResources().getIdentifier("" + resourceName, "raw", context.getPackageName());
  }
}
