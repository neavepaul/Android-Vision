package com.neave.mobilenet;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;

import com.neave.mobilenet.R;

import com.neave.mobilenet.vision.VisionListActivity;

import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_main);
    Handler handler = new Handler();

    findViewById(R.id.main_vision_click_view).setOnClickListener(v -> moveFromSplash());
    // OR
    handler.postDelayed(new Runnable() {
      @Override
      public void run() {
        moveFromSplash();

      }
    }, 1500);

  }

  @Override
  protected void onStart() {
    super.onStart();
    setContentView(R.layout.activity_main);
    Handler handler = new Handler();

    findViewById(R.id.main_vision_click_view).setOnClickListener(v -> moveFromSplash());
    // OR
    handler.postDelayed(new Runnable() {
      @Override
      public void run() {
        moveFromSplash();

      }
    }, 1500);
  }

  private void moveFromSplash() {
    startActivity(new Intent(MainActivity.this, VisionListActivity.class));
    overridePendingTransition(android.R.anim.slide_in_left, android.R.anim.slide_out_right);
  }
}
