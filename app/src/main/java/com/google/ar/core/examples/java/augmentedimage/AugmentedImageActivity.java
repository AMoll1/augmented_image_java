/*
 * Copyright 2018 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ar.core.examples.java.augmentedimage;

import android.net.Uri;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.util.Log;
import android.util.Pair;
import android.view.View;
import android.widget.ImageView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.bumptech.glide.Glide;
import com.bumptech.glide.RequestManager;
import com.google.ar.core.Anchor;
import com.google.ar.core.ArCoreApk;
import com.google.ar.core.AugmentedImage;
import com.google.ar.core.AugmentedImageDatabase;
import com.google.ar.core.Camera;
import com.google.ar.core.Config;
import com.google.ar.core.Frame;
import com.google.ar.core.Pose;
import com.google.ar.core.Session;
import com.google.ar.core.examples.java.augmentedimage.rendering.AugmentedImageRenderer;
import com.google.ar.core.examples.java.common.helpers.CameraPermissionHelper;
import com.google.ar.core.examples.java.common.helpers.DisplayRotationHelper;
import com.google.ar.core.examples.java.common.helpers.FullScreenHelper;
import com.google.ar.core.examples.java.common.helpers.SnackbarHelper;
import com.google.ar.core.examples.java.common.helpers.TrackingStateHelper;
import com.google.ar.core.examples.java.common.rendering.BackgroundRenderer;
import com.google.ar.core.exceptions.CameraNotAvailableException;
import com.google.ar.core.exceptions.UnavailableApkTooOldException;
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException;
import com.google.ar.core.exceptions.UnavailableSdkTooOldException;
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * This app extends the HelloAR Java app to include image tracking functionality.
 *
 * <p>In this example, we assume all images are static or moving slowly with a large occupation of
 * the screen. If the target is actively moving, we recommend to check
 * AugmentedImage.getTrackingMethod() and render only when the tracking method equals to
 * FULL_TRACKING. See details in <a
 * href="https://developers.google.com/ar/develop/java/augmented-images/">Recognize and Augment
 * Images</a>.
 */

public class AugmentedImageActivity extends AppCompatActivity implements GLSurfaceView.Renderer {
  private static final String TAG = AugmentedImageActivity.class.getSimpleName();

  // Rendering. The Renderers are created here, and initialized when the GL surface is created.
  private GLSurfaceView surfaceView;
  private ImageView fitToScanView;
  private RequestManager glideRequestManager;

  private boolean installRequested;

  private Session session;
  private final SnackbarHelper messageSnackbarHelper = new SnackbarHelper();
  private DisplayRotationHelper displayRotationHelper;
  private final TrackingStateHelper trackingStateHelper = new TrackingStateHelper(this);

  private final BackgroundRenderer backgroundRenderer = new BackgroundRenderer();
  private final AugmentedImageRenderer augmentedImageRenderer = new AugmentedImageRenderer();

  private boolean shouldConfigureSession = false;

  // Augmented image and its associated center pose anchor, keyed by index of the augmented image in
  // the
  // database.
  private final Map<Integer, Pair<AugmentedImage, Anchor>> augmentedImageMap = new HashMap<>();

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_main);
    surfaceView = findViewById(R.id.surfaceview);
    displayRotationHelper = new DisplayRotationHelper(/*context=*/ this);

    // Set up renderer.
    surfaceView.setPreserveEGLContextOnPause(true);
    surfaceView.setEGLContextClientVersion(2);
    surfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0); // Alpha used for plane blending.
    surfaceView.setRenderer(this);
    surfaceView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
    surfaceView.setWillNotDraw(false);

    fitToScanView = findViewById(R.id.image_view_fit_to_scan);
    glideRequestManager = Glide.with(this);

    glideRequestManager
        .load(Uri.parse("file:///android_asset/fit_to_scan.png"))
        .into(fitToScanView);

    installRequested = false;
  }

  @Override
  protected void onDestroy() {
    if (session != null) {
      // Explicitly close ARCore Session to release native resources.
      // Review the API reference for important considerations before calling close() in apps with
      // more complicated lifecycle requirements:
      // https://developers.google.com/ar/reference/java/arcore/reference/com/google/ar/core/Session#close()
      session.close();
      session = null;
    }
    super.onDestroy();
  }

  @Override
  protected void onResume() {
    super.onResume();

    if (session == null) {
      Exception exception = null;
      String message = null;
      try {
        switch (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
          case INSTALL_REQUESTED:
            installRequested = true;
            return;
          case INSTALLED:
            break;
        }

        // ARCore requires camera permissions to operate. If we did not yet obtain runtime
        // permission on Android M and above, now is a good time to ask the user for it.
        if (!CameraPermissionHelper.hasCameraPermission(this)) {
          CameraPermissionHelper.requestCameraPermission(this);
          return;
        }

        session = new Session(/* context = */ this);
      } catch (UnavailableArcoreNotInstalledException
          | UnavailableUserDeclinedInstallationException e) {
        message = "Please install ARCore";
        exception = e;
      } catch (UnavailableApkTooOldException e) {
        message = "Please update ARCore";
        exception = e;
      } catch (UnavailableSdkTooOldException e) {
        message = "Please update this app";
        exception = e;
      } catch (Exception e) {
        message = "This device does not support AR";
        exception = e;
      }

      if (message != null) {
        messageSnackbarHelper.showError(this, message);
        Log.e(TAG, "Exception creating session", exception);
        return;
      }

      shouldConfigureSession = true;
    }

    if (shouldConfigureSession) {
      configureSession();
      shouldConfigureSession = false;
    }

    // Note that order matters - see the note in onPause(), the reverse applies here.
    try {
      session.resume();
    } catch (CameraNotAvailableException e) {
      messageSnackbarHelper.showError(this, "Camera not available. Try restarting the app.");
      session = null;
      return;
    }
    surfaceView.onResume();
    displayRotationHelper.onResume();

    fitToScanView.setVisibility(View.VISIBLE);
  }

  @Override
  public void onPause() {
    super.onPause();
    if (session != null) {
      // Note that the order matters - GLSurfaceView is paused first so that it does not try
      // to query the session. If Session is paused before GLSurfaceView, GLSurfaceView may
      // still call session.update() and get a SessionPausedException.
      displayRotationHelper.onPause();
      surfaceView.onPause();
      session.pause();
    }
  }

  @Override
  public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
    super.onRequestPermissionsResult(requestCode, permissions, results);
    if (!CameraPermissionHelper.hasCameraPermission(this)) {
      Toast.makeText(
              this, "Camera permissions are needed to run this application", Toast.LENGTH_LONG)
          .show();
      if (!CameraPermissionHelper.shouldShowRequestPermissionRationale(this)) {
        // Permission denied with checking "Do not ask again".
        CameraPermissionHelper.launchPermissionSettings(this);
      }
      finish();
    }
  }

  @Override
  public void onWindowFocusChanged(boolean hasFocus) {
    super.onWindowFocusChanged(hasFocus);
    FullScreenHelper.setFullScreenOnWindowFocusChanged(this, hasFocus);
  }

  @Override
  public void onSurfaceCreated(GL10 gl, EGLConfig config) {
    GLES20.glClearColor(0.1f, 0.1f, 0.1f, 1.0f);

    // Prepare the rendering objects. This involves reading shaders, so may throw an IOException.
    try {
      // Create the texture and pass it to ARCore session to be filled during update().
      backgroundRenderer.createOnGlThread(/*context=*/ this);
      augmentedImageRenderer.createOnGlThread(/*context=*/ this);
    } catch (IOException e) {
      Log.e(TAG, "Failed to read an asset file", e);
    }
  }

  @Override
  public void onSurfaceChanged(GL10 gl, int width, int height) {
    displayRotationHelper.onSurfaceChanged(width, height);
    GLES20.glViewport(0, 0, width, height);
  }

  @Override
  public void onDrawFrame(GL10 gl) {
    // Clear screen to notify driver it should not load any pixels from previous frame.
    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);

    if (session == null) {
      return;
    }
    // Notify ARCore session that the view size changed so that the perspective matrix and
    // the video background can be properly adjusted.
    displayRotationHelper.updateSessionIfNeeded(session);

    try {
      session.setCameraTextureName(backgroundRenderer.getTextureId());

      // Obtain the current frame from ARSession. When the configuration is set to
      // UpdateMode.BLOCKING (it is by default), this will throttle the rendering to the
      // camera framerate.
      Frame frame = session.update();
      Camera camera = frame.getCamera();

      // Keep the screen unlocked while tracking, but allow it to lock when tracking stops.
      trackingStateHelper.updateKeepScreenOnFlag(camera.getTrackingState());

      // If frame is ready, render camera preview image to the GL surface.
      backgroundRenderer.draw(frame);

      // Get projection matrix.
      float[] projmtx = new float[16];
      camera.getProjectionMatrix(projmtx, 0, 0.001f, 15.0f);


      // Get camera matrix and draw.
      float[] viewmtx = new float[16];
      camera.getViewMatrix(viewmtx, 0);
      //Log.i("Pose", viewmtx.toString());


      // Compute lighting from average intensity of the image.
      final float[] colorCorrectionRgba = new float[4];
      frame.getLightEstimate().getColorCorrection(colorCorrectionRgba, 0);

      // Visualize augmented images.
      drawAugmentedImages(frame, projmtx, viewmtx, colorCorrectionRgba);
    } catch (Throwable t) {
      // Avoid crashing the application due to unhandled exceptions.
      Log.e(TAG, "Exception on the OpenGL thread", t);
    }
  }

  private void configureSession() {
    Config config = new Config(session);
    config.setFocusMode(Config.FocusMode.AUTO);
//    config.setUpdateMode(Config.UpdateMode.LATEST_CAMERA_IMAGE);
    if (!setupAugmentedImageDatabase(config)) {
      messageSnackbarHelper.showError(this, "Could not setup augmented image database");
    }
    session.configure(config);
  }


  private void drawAugmentedImages(
      Frame frame, float[] projmtx, float[] viewmtx, float[] colorCorrectionRgba) {
    Collection<AugmentedImage> updatedAugmentedImages = frame.getUpdatedTrackables(AugmentedImage.class);
    double tempX,tempY, realX, realY, deltaPercentX, deltaPercentY;
    int placeOfInterestX, placeOfInterestY, deltaX, deltaY;
    double[][] homographyMatrix = new double[3][3];
    float [] poi = new float[63];
    float aiWidth, aiHeight;
    Pose transformPose;
    Pose center_Pose;


////////////////////////////////////////////////////////////////////////////////////////////
//    //remove not updated augmented Images from Map
//    int [] updatedAugmentedImagesIndexes = new int [updatedAugmentedImages.size()];
//    int i=0;
//    for (AugmentedImage augmentedImage : updatedAugmentedImages) {
//      updatedAugmentedImagesIndexes[i]=augmentedImage.getIndex();
//      i++;
//    }
//    Log.i("UpdatedAugmentedImages ", Arrays.toString(updatedAugmentedImagesIndexes));
//    for (int index : augmentedImageMap.keySet()){
//      if (Arrays.stream(updatedAugmentedImagesIndexes).anyMatch(num -> num==index)){
//        continue;
//      }else{
//        augmentedImageMap.remove(index);
//      }
//    }
    ////////////////////////////////////////////////////////////////////////////////////////


    // Iterate to update augmentedImageMap, remove elements we cannot draw.
    for (AugmentedImage augmentedImage : updatedAugmentedImages) {
      switch (augmentedImage.getTrackingState()) {

        case PAUSED:
          // When an image is in PAUSED state, but the camera is not PAUSED, it has been detected,
          // but not yet tracked.

          String text = String.format("Detected Image %d", augmentedImage.getIndex());
          messageSnackbarHelper.showMessage(this, text);
          //augmentedImageMap.remove(augmentedImage.getIndex());
          break;

        case TRACKING:
          if (augmentedImage.getTrackingMethod() == AugmentedImage.TrackingMethod.FULL_TRACKING) {
            //if(true){
            //Log.i("Coordinates " + String.valueOf(augmentedImage.getIndex()), String.valueOf(augmentedImage.getIndex()));

            // Have to switch to UI Thread to update View.
            this.runOnUiThread(
                    new Runnable() {
                      @Override
                      public void run() {
                        fitToScanView.setVisibility(View.GONE);
                      }
                    });

            if (augmentedImage.getIndex() == 0) {
              poi = new float[]{46.6711523f, 12.990682f}; // (lat (breiten), lon)

              homographyMatrix[0][0] = -14.422063108068571f;
              homographyMatrix[0][1] = -6.202500365062806f;
              homographyMatrix[0][2] = -0.006514659866530f;
              homographyMatrix[1][0] = 8.624838277731961f;
              homographyMatrix[1][1] = -10.938686926384731f;
              homographyMatrix[1][2] = -0.020101862247444f;
              homographyMatrix[2][0] = -222.957613616915240f;
              homographyMatrix[2][1] = 593.607517440842800f;
              homographyMatrix[2][2] = 1.000000000000000f;
            }


            if (augmentedImage.getIndex() == 1) {
              poi = new float[]{46.6711523f, 12.990682f};

              homographyMatrix[0][0] = -12.525025605439273f;
              homographyMatrix[0][1] = -3.316428442094773f;
              homographyMatrix[0][2] = -0.009649690505538f;
              homographyMatrix[1][0] = 5.893967641849964f;
              homographyMatrix[1][1] = -5.050425957572911f;
              homographyMatrix[1][2] = -0.019133954213306f;
              homographyMatrix[2][0] = -118.491163432536080f;
              homographyMatrix[2][1] = 283.345124765631000f;
              homographyMatrix[2][2] = 1.000000000000000f;
            }

            if (augmentedImage.getIndex() == 2) {
              poi = new float[]{46.6711523f, 12.990682f};

              homographyMatrix[0][0] = -12.078091183470349f;
              homographyMatrix[0][1] = -2.425983207890170f;
              homographyMatrix[0][2] = -0.017421162077561f;
              homographyMatrix[1][0] = 12.276767885569784f;
              homographyMatrix[1][1] = -1.303481056898708f;
              homographyMatrix[1][2] = -0.016991612659412f;
              homographyMatrix[2][0] = -419.844654885400930f;
              homographyMatrix[2][1] = 99.322904692444084f;
              homographyMatrix[2][2] = 1.000000000000000f;
            }

            if (augmentedImage.getIndex() == 3) {
              poi = new float[]{46.6711523f, 12.990682f};

              homographyMatrix[0][0] = -21.289940662320724f;
              homographyMatrix[0][1] = -1.263880636391958f;
              homographyMatrix[0][2] = -0.002165061163928f;
              homographyMatrix[1][0] = -1.048026785057320f;
              homographyMatrix[1][1] = -20.959101012878165f;
              homographyMatrix[1][2] = -0.021391282862282f;
              homographyMatrix[2][0] = 313.643697385763120f;
              homographyMatrix[2][1] = 997.912566187789710f;
              homographyMatrix[2][2] = 1.000000000000000f;
            }

            if (augmentedImage.getIndex() == 4) {
              poi = new float[]{46.6711523f, 12.990682f};

              homographyMatrix[0][0] = -12.793377336733231f;
              homographyMatrix[0][1] = -2.156219795373052f;
              homographyMatrix[0][2] = -0.007245467550403f;
              homographyMatrix[1][0] = 1.953391776403074f;
              homographyMatrix[1][1] = -6.553023160006035f;
              homographyMatrix[1][2] = -0.019600516094198f;
              homographyMatrix[2][0] = 69.505940535352821f;
              homographyMatrix[2][1] = 338.018483364489100f;
              homographyMatrix[2][2] = 1.000000000000000f;
            }

            if (augmentedImage.getIndex() == 5) {
              poi = new float[]{46.6711523f, 12.990682f};
              homographyMatrix[0][0] = -15.076904840916457f;
              homographyMatrix[0][1] = -2.851711916796907f;
              homographyMatrix[0][2] = -0.020256501539995f;
              homographyMatrix[1][0] = 15.118051437020389f;
              homographyMatrix[1][1] = -3.326986167431872f;
              homographyMatrix[1][2] = -0.016231979465247f;
              homographyMatrix[2][0] = -512.469711779482620f;
              homographyMatrix[2][1] = 200.854202977159500f;
              homographyMatrix[2][2] = 1.000000000000000f;
            }

            if (augmentedImage.getIndex() == 6) {
              poi = new float[]{46.6711523f, 12.990682f};
              homographyMatrix[0][0] = -19.333580466764879f;
              homographyMatrix[0][1] = -1.201991171566263f;
              homographyMatrix[0][2] = 0.001136187911339f;
              homographyMatrix[1][0] = -3.785784123809914f;
              homographyMatrix[1][1] = -19.382496884899776f;
              homographyMatrix[1][2] = -0.022266832555946f;
              homographyMatrix[2][0] = 418.359692377769760f;
              homographyMatrix[2][1] = 923.638728133192790f;
              homographyMatrix[2][2] = 1.000000000000000f;
            }

            if (augmentedImage.getIndex() == 7) {
              poi = new float[]{46.6711523f, 12.990682f};
              homographyMatrix[0][0] = -12.795657555293165f;
              homographyMatrix[0][1] = -1.746219969261772f;
              homographyMatrix[0][2] = -0.004810832554607f;
              homographyMatrix[1][0] = -0.338757253809990f;
              homographyMatrix[1][1] = -7.473445217873321f;
              homographyMatrix[1][2] = -0.020427222875539f;
              homographyMatrix[2][0] = 177.049545895767320f;
              homographyMatrix[2][1] = 375.690863842849350f;
              homographyMatrix[2][2] = 1.000000000000000f;
            }

            if (augmentedImage.getIndex() == 8) {
              poi = new float[]{46.6711523f, 12.990682f};
              homographyMatrix[0][0] = -14.857677662696501f;
              homographyMatrix[0][1] = -2.873886465603464f;
              homographyMatrix[0][2] = -0.019182704527798f;
              homographyMatrix[1][0] = 14.170436714032135f;
              homographyMatrix[1][1] = -3.470998904096363f;
              homographyMatrix[1][2] = -0.016473776877201f;
              homographyMatrix[2][0] = -469.558854241888810f;
              homographyMatrix[2][1] = 207.746270535192370f;
              homographyMatrix[2][2] = 1.000000000000000f;
            }

            if (augmentedImage.getIndex() == 9) {
              poi = new float[]{46.6711523f, 12.990682f};
              homographyMatrix[0][0] = -11.192996660108999f;
              homographyMatrix[0][1] = -0.732719142711541f;
              homographyMatrix[0][2] = 0.004598042730244f;
              homographyMatrix[1][0] = -6.444606253143467f;
              homographyMatrix[1][1] = -9.838260423992843f;
              homographyMatrix[1][2] = -0.023052032100913f;
              homographyMatrix[2][0] = 440.678387614563520f;
              homographyMatrix[2][1] = 471.792180586151910f;
              homographyMatrix[2][2] = 1.000000000000000f;
            }

            if (augmentedImage.getIndex() == 10) {
              poi = new float[]{46.6711523f, 12.990682f};
              homographyMatrix[0][0] = -12.333971489236722f;
              homographyMatrix[0][1] = -0.762810192534510f;
              homographyMatrix[0][2] = -0.002447993537345f;
              homographyMatrix[1][0] = -3.451117758168849f;
              homographyMatrix[1][1] = -7.566466634123137f;
              homographyMatrix[1][2] = -0.021070068025574f;
              homographyMatrix[2][0] = 317.240612262460730f;
              homographyMatrix[2][1] = 367.347161558546250f;
              homographyMatrix[2][2] = 1.000000000000000f;
            }

            if (augmentedImage.getIndex() == 11) {
              poi = new float[]{46.6711523f, 12.990682f};
              homographyMatrix[0][0] = -15.026106544871931f;
              homographyMatrix[0][1] = -3.988421419360942f;
              homographyMatrix[0][2] = -0.019716179685793f;
              homographyMatrix[1][0] = 16.101817682976687f;
              homographyMatrix[1][1] = -4.080237716939084f;
              homographyMatrix[1][2] = -0.016296438703696f;
              homographyMatrix[2][0] = -555.087983042524910f;
              homographyMatrix[2][1] = 251.437581183341880f;
              homographyMatrix[2][2] = 1.000000000000000f;
            }

            if (augmentedImage.getIndex() == 12) {
              poi = new float[]{46.6711523f, 12.990682f};
              homographyMatrix[0][0] = -7.985463608129596f;
              homographyMatrix[0][1] = 0.662844048949804f;
              homographyMatrix[0][2] = 0.008009485104931f;
              homographyMatrix[1][0] = -11.303226184748606f;
              homographyMatrix[1][1] = -5.516361646038969f;
              homographyMatrix[1][2] = -0.023971890098929f;
              homographyMatrix[2][0] = 626.475566839339650f;
              homographyMatrix[2][1] = 252.733836655493520f;
              homographyMatrix[2][2] = 1.000000000000000f;
            }

            if (augmentedImage.getIndex() == 13) {
              poi = new float[]{46.6711523f, 12.990682f};
              homographyMatrix[0][0] = -13.107693030926654f;
              homographyMatrix[0][1] = -0.825824085859970f;
              homographyMatrix[0][2] = -0.002021767916259f;
              homographyMatrix[1][0] = -3.924919233748125f;
              homographyMatrix[1][1] = -8.502265267166591f;
              homographyMatrix[1][2] = -0.021214539682937f;
              homographyMatrix[2][0] = 350.992333132208900f;
              homographyMatrix[2][1] = 412.427053308210700f;
              homographyMatrix[2][2] = 1.000000000000000f;
            }

            if (augmentedImage.getIndex() == 14) {
              poi = new float[]{46.6711523f, 12.990682f};
              homographyMatrix[0][0] = -25.741983393899023f;
              homographyMatrix[0][1] = -0.875807929240497f;
              homographyMatrix[0][2] = -0.003005833798230f;
              homographyMatrix[1][0] = -4.623153628996831f;
              homographyMatrix[1][1] = -27.578678897410501f;
              homographyMatrix[1][2] = -0.021240513865595f;
              homographyMatrix[2][0] = 545.501884509131630f;
              homographyMatrix[2][1] = 1301.820866778109300f;
              homographyMatrix[2][2] = 1.000000000000000f;
            }

            if (augmentedImage.getIndex() == 15) {
              poi = new float[]{46.6711523f, 12.990682f};
              homographyMatrix[0][0] = -14.971708761433744f;
              homographyMatrix[0][1] = -3.840709813716803f;
              homographyMatrix[0][2] = -0.018237969639556f;
              homographyMatrix[1][0] = 14.723743784096163f;
              homographyMatrix[1][1] = -4.277309124936046f;
              homographyMatrix[1][2] = -0.016647824735842f;
              homographyMatrix[2][0] = -489.333394472595330f;
              homographyMatrix[2][1] = 258.941900790476780f;
              homographyMatrix[2][2] = 1.000000000000000f;
            }

            if (augmentedImage.getIndex() == 16) {
              poi = new float[]{46.6711523f, 12.990682f};
              homographyMatrix[0][0] = -9.883722019099944f;
              homographyMatrix[0][1] = 2.711205552056606f;
              homographyMatrix[0][2] = 0.006751562914579f;
              homographyMatrix[1][0] = -16.012966539265637f;
              homographyMatrix[1][1] = -6.441929463575636f;
              homographyMatrix[1][2] = -0.023710204289736f;
              homographyMatrix[2][0] = 870.209520697415540f;
              homographyMatrix[2][1] = 270.496404511258050f;
              homographyMatrix[2][2] = 1.000000000000000f;
            }

            if (augmentedImage.getIndex() == 17) {
              poi = new float[]{46.6711523f, 12.990682f};
              homographyMatrix[0][0] = -12.829592406637035f;
              homographyMatrix[0][1] = -0.655605810585537f;
              homographyMatrix[0][2] = -0.001575147726983f;
              homographyMatrix[1][0] = -4.188088424317749f;
              homographyMatrix[1][1] = -8.593359321793470f;
              homographyMatrix[1][2] = -0.021331233370917f;
              homographyMatrix[2][0] = 361.455880776352390f;
              homographyMatrix[2][1] = 414.029256641065220f;
              homographyMatrix[2][2] = 1.000000000000000f;
            }

            if (augmentedImage.getIndex() == 18) {
              poi = new float[]{46.6711523f, 12.990682f};
              homographyMatrix[0][0] = -21.309883764182853f;
              homographyMatrix[0][1] = -1.216163038973580f;
              homographyMatrix[0][2] = -0.002152618143576f;
              homographyMatrix[1][0] = -3.378362943220401f;
              homographyMatrix[1][1] = -22.354143828856110f;
              homographyMatrix[1][2] = -0.021370970170447f;
              homographyMatrix[2][0] = 433.711607570908940f;
              homographyMatrix[2][1] = 1062.156736492308000f;
              homographyMatrix[2][2] = 1.000000000000000f;
            }

            if (augmentedImage.getIndex() == 19) {
              poi = new float[]{46.8322843f, 14.8161273f};
              homographyMatrix[0][0] = -13.709328194964236f;
              homographyMatrix[0][1] = -2.895788080911679f;
              homographyMatrix[0][2] = -0.015118338098735f;
              homographyMatrix[1][0] = 9.958151024833514f;
              homographyMatrix[1][1] = -4.518216213447853f;
              homographyMatrix[1][2] = -0.017431510682122f;
              homographyMatrix[2][0] = -282.180269410189400f;
              homographyMatrix[2][1] = 256.390604160046560f;
              homographyMatrix[2][2] = 1.000000000000000f;
            }

            if (augmentedImage.getIndex() == 20) {
              poi = new float[]{46.6711523f, 12.990682f};
              homographyMatrix[0][0] = -9.003838834386729f;
              homographyMatrix[0][1] = 3.360883452486947f;
              homographyMatrix[0][2] = 0.010036009137735f;
              homographyMatrix[1][0] = -19.310177652225676f;
              homographyMatrix[1][1] = -6.489806034180328f;
              homographyMatrix[1][2] = -0.024703502314468f;
              homographyMatrix[2][0] = 1012.557268644473800f;
              homographyMatrix[2][1] = 264.425350118694210f;
              homographyMatrix[2][2] = 1.000000000000000f;
            }

            if (augmentedImage.getIndex() == 21) {
              poi = new float[]{46.6711523f, 12.990682f};
              homographyMatrix[0][0] = -12.499380495378100f;
              homographyMatrix[0][1] = -0.411737808528891f;
              homographyMatrix[0][2] = -0.001331344116644f;
              homographyMatrix[1][0] = -5.168647266320340f;
              homographyMatrix[1][1] = -7.954843128633883f;
              homographyMatrix[1][2] = -0.021387742618412f;
              homographyMatrix[2][0] = 404.706107274224960f;
              homographyMatrix[2][1] = 380.913071951317870f;
              homographyMatrix[2][2] = 1.000000000000000f;
            }

            if (augmentedImage.getIndex() == 22) {
              poi = new float[]{46.6711523f, 12.990682f};
              homographyMatrix[0][0] = -15.880929726248574f;
              homographyMatrix[0][1] = -0.485692381001937f;
              homographyMatrix[0][2] = -0.001152706943188f;
              homographyMatrix[1][0] = -5.546130233364831f;
              homographyMatrix[1][1] = -15.182083281780971f;
              homographyMatrix[1][2] = -0.021525030648216f;
              homographyMatrix[2][0] = 466.572069983971570f;
              homographyMatrix[2][1] = 717.184719564471610f;
              homographyMatrix[2][2] = 1.000000000000000f;
            }

            if (augmentedImage.getIndex() == 23) {
              poi = new float[]{46.8322843f, 14.8161273f};
              homographyMatrix[0][0] = -13.991511175109459f;
              homographyMatrix[0][1] = -3.879086977499831f;
              homographyMatrix[0][2] = -0.016379195026477f;
              homographyMatrix[1][0] = 13.150753266388511f;
              homographyMatrix[1][1] = -3.925083870024159f;
              homographyMatrix[1][2] = -0.017057408928091f;
              homographyMatrix[2][0] = -424.759875530936880f;
              homographyMatrix[2][1] = 243.557200097980600f;
              homographyMatrix[2][2] = 1.000000000000000f;
            }

            if (augmentedImage.getIndex() == 24) {
              poi = new float[]{46.6711523f, 12.990682f};
              homographyMatrix[0][0] = -8.136473575057789f;
              homographyMatrix[0][1] = 4.878436339407631f;
              homographyMatrix[0][2] = 0.015010474846251f;
              homographyMatrix[1][0] = -26.536982957763094f;
              homographyMatrix[1][1] = -6.474370011486249f;
              homographyMatrix[1][2] = -0.026258398888309f;
              homographyMatrix[2][0] = 1335.585955500133200f;
              homographyMatrix[2][1] = 245.030827987248840f;
              homographyMatrix[2][2] = 1.000000000000000f;
            }

            if (augmentedImage.getIndex() == 25) {
              poi = new float[]{46.6711523f, 12.990682f};
              homographyMatrix[0][0] = -13.003746918685215f;
              homographyMatrix[0][1] = -0.139160735760162f;
              homographyMatrix[0][2] = -0.002508229955243f;
              homographyMatrix[1][0] = -4.844862544489469f;
              homographyMatrix[1][1] = -8.118651128142275f;
              homographyMatrix[1][2] = -0.021047491945698f;
              homographyMatrix[2][0] = 398.255675828832860f;
              homographyMatrix[2][1] = 384.964176697712960f;
              homographyMatrix[2][2] = 1.000000000000000f;
            }

            if (augmentedImage.getIndex() == 26) {
              poi = new float[]{46.6711523f, 12.990682f};
              homographyMatrix[0][0] = -16.955968586924218f;
              homographyMatrix[0][1] = -0.602397746982883f;
              homographyMatrix[0][2] = -0.001026157351505f;
              homographyMatrix[1][0] = -5.394753701185071f;
              homographyMatrix[1][1] = -16.792994601225772f;
              homographyMatrix[1][2] = -0.021586019813241f;
              homographyMatrix[2][0] = 475.943946230457580f;
              homographyMatrix[2][1] = 793.834908346914180f;
              homographyMatrix[2][2] = 1.000000000000000f;
            }

            if (augmentedImage.getIndex() == 27) {
              poi = new float[]{46.8322843f, 14.8161273f};
              homographyMatrix[0][0] = -12.920487877633644f;
              homographyMatrix[0][1] = -3.679590900049870f;
              homographyMatrix[0][2] = -0.015177797871784f;
              homographyMatrix[1][0] = 10.706883057366777f;
              homographyMatrix[1][1] = -3.309018700453784f;
              homographyMatrix[1][2] = -0.017317480379212f;
              homographyMatrix[2][0] = -323.324016363295870f;
              homographyMatrix[2][1] = 211.693275805786160f;
              homographyMatrix[2][2] = 1.000000000000000f;
            }

            if (augmentedImage.getIndex() == 28) {
              poi = new float[]{46.6711523f, 12.990682f};
              homographyMatrix[0][0] = -8.483692401965714f;
              homographyMatrix[0][1] = 3.819258131528225f;
              homographyMatrix[0][2] = 0.017829677549595f;
              homographyMatrix[1][0] = -26.906230292150507f;
              homographyMatrix[1][1] = -7.254774839280151f;
              homographyMatrix[1][2] = -0.027141449088290f;
              homographyMatrix[2][0] = 1358.424636445689900f;
              homographyMatrix[2][1] = 295.482036961544680f;
              homographyMatrix[2][2] = 1.000000000000000f;
            }

            if (augmentedImage.getIndex() == 29) {
              poi = new float[]{46.6711523f, 12.990682f};
              homographyMatrix[0][0] = -12.781652018610396f;
              homographyMatrix[0][1] = -0.671807254496863f;
              homographyMatrix[0][2] = -0.000185601529147f;
              homographyMatrix[1][0] = -4.958381242060012f;
              homographyMatrix[1][1] = -8.724999310320229f;
              homographyMatrix[1][2] = -0.021741936591731f;
              homographyMatrix[2][0] = 401.786719631263110f;
              homographyMatrix[2][1] = 420.758566756693820f;
              homographyMatrix[2][2] = 1.000000000000000f;
            }

            if (augmentedImage.getIndex() == 30) {
              poi = new float[]{46.6711523f, 12.990682f};
              homographyMatrix[0][0] = -22.719039386299940f;
              homographyMatrix[0][1] = -0.558504313825298f;
              homographyMatrix[0][2] = -0.001408846583561f;
              homographyMatrix[1][0] = -4.393068327827728f;
              homographyMatrix[1][1] = -24.711599878882851f;
              homographyMatrix[1][2] = -0.021618224899540f;
              homographyMatrix[2][0] = 508.612090153432630f;
              homographyMatrix[2][1] = 1162.886207000678700f;
              homographyMatrix[2][2] = 1.000000000000000f;
            }

            if (augmentedImage.getIndex() == 31) {
              poi = new float[]{46.8322843f, 14.8161273f};
              homographyMatrix[0][0] = -12.812285340590080f;
              homographyMatrix[0][1] = -3.458280170507699f;
              homographyMatrix[0][2] = -0.013682932341602f;
              homographyMatrix[1][0] = 9.210440903665546f;
              homographyMatrix[1][1] = -3.889766901942193f;
              homographyMatrix[1][2] = -0.017717824661652f;
              homographyMatrix[2][0] = -253.635864856090680f;
              homographyMatrix[2][1] = 235.686657298710770f;
              homographyMatrix[2][2] = 1.000000000000000f;
            }

            if (augmentedImage.getIndex() == 32) {
              poi = new float[]{46.6711523f, 12.990682f};
              homographyMatrix[0][0] = -8.009439372999022f;
              homographyMatrix[0][1] = 3.623423051979003f;
              homographyMatrix[0][2] = 0.015544413643216f;
              homographyMatrix[1][0] = -24.902546540961147f;
              homographyMatrix[1][1] = -5.422149107879927f;
              homographyMatrix[1][2] = -0.026489155395761f;
              homographyMatrix[2][0] = 1261.211298338459100f;
              homographyMatrix[2][1] = 211.555122926624730f;
              homographyMatrix[2][2] = 1.000000000000000f;
            }

            if (augmentedImage.getIndex() == 33) {
              poi = new float[]{46.6711523f, 12.990682f};
              homographyMatrix[0][0] = -13.532403552751902f;
              homographyMatrix[0][1] = -0.157754984986451f;
              homographyMatrix[0][2] = 0.000264870665213f;
              homographyMatrix[1][0] = -6.703250365878294f;
              homographyMatrix[1][1] = -9.807315428130416f;
              homographyMatrix[1][2] = -0.021901703466823f;
              homographyMatrix[2][0] = 494.661406099931010f;
              homographyMatrix[2][1] = 464.338304138526720f;
              homographyMatrix[2][2] = 1.000000000000000f;
            }

            if (augmentedImage.getIndex() == 34) {
              poi = new float[]{46.6711523f, 12.990682f};
              homographyMatrix[0][0] = -19.170570489378630f;
              homographyMatrix[0][1] = -0.648399768751012f;
              homographyMatrix[0][2] = 0.000419593091722f;
              homographyMatrix[1][0] = -5.846854607102455f;
              homographyMatrix[1][1] = -19.855294300343289f;
              homographyMatrix[1][2] = -0.022087881270256f;
              homographyMatrix[2][0] = 531.159121851138250f;
              homographyMatrix[2][1] = 937.913578174124720f;
              homographyMatrix[2][2] = 1.000000000000000f;
            }

            if (augmentedImage.getIndex() == 35) {
              poi = new float[]{46.8322843f, 14.8161273f};
              homographyMatrix[0][0] = -14.876529043139735f;
              homographyMatrix[0][1] = -5.056763019716333f;
              homographyMatrix[0][2] = -0.015035258233501f;
              homographyMatrix[1][0] = 12.842137214809830f;
              homographyMatrix[1][1] = -6.157393148379252f;
              homographyMatrix[1][2] = -0.017359505910513f;
              homographyMatrix[2][0] = -392.301891584459440f;
              homographyMatrix[2][1] = 365.889793810553950f;
              homographyMatrix[2][2] = 1.000000000000000f;
            }

            if (augmentedImage.getIndex() == 36) {
              poi = new float[]{46.6711523f, 12.990682f};
              homographyMatrix[0][0] = -8.084115079147962f;
              homographyMatrix[0][1] = 3.534704266734118f;
              homographyMatrix[0][2] = 0.017911971589301f;
              homographyMatrix[1][0] = -26.966958669506646f;
              homographyMatrix[1][1] = -6.650028615446627f;
              homographyMatrix[1][2] = -0.027284175242711f;
              homographyMatrix[2][0] = 1357.937126949284400f;
              homographyMatrix[2][1] = 270.068658931730910f;
              homographyMatrix[2][2] = 1.000000000000000f;
            }

            if (augmentedImage.getIndex() == 37) {
              poi = new float[]{46.8322843f, 14.8161273f};
              homographyMatrix[0][0] = -12.637426951649054f;
              homographyMatrix[0][1] = -0.599904718558768f;
              homographyMatrix[0][2] = -0.001128077625952f;
              homographyMatrix[1][0] = -5.354334817277028f;
              homographyMatrix[1][1] = -8.676621636984871f;
              homographyMatrix[1][2] = -0.021456937697826f;
              homographyMatrix[2][0] = 422.274681462714850f;
              homographyMatrix[2][1] = 417.411763681711420f;
              homographyMatrix[2][2] = 1.000000000000000f;
            }

            if (augmentedImage.getIndex() == 38) {
              poi = new float[]{46.6711523f, 12.990682f};
              homographyMatrix[0][0] = -19.126763683668877f;
              homographyMatrix[0][1] = -0.856179065072740f;
              homographyMatrix[0][2] = -0.001938325683655f;
              homographyMatrix[1][0] = -4.477247049434404f;
              homographyMatrix[1][1] = -19.425343482488522f;
              homographyMatrix[1][2] = -0.021362564173142f;
              homographyMatrix[2][0] = 470.485268616618900f;
              homographyMatrix[2][1] = 920.480517906363730f;
              homographyMatrix[2][2] = 1.000000000000000f;
            }

            if (augmentedImage.getIndex() == 39) {
              poi = new float[]{46.8322843f, 14.8161273f};
              homographyMatrix[0][0] = -14.705812443707661f;
              homographyMatrix[0][1] = -4.715476300320320f;
              homographyMatrix[0][2] = -0.013811173435507f;
              homographyMatrix[1][0] = 10.813032840721814f;
              homographyMatrix[1][1] = -6.336409842709247f;
              homographyMatrix[1][2] = -0.017662268789792f;
              homographyMatrix[2][0] = -297.974258342555520f;
              homographyMatrix[2][1] = 369.289927541455710f;
              homographyMatrix[2][2] = 1.000000000000000f;
            }

            if (augmentedImage.getIndex() == 40) {
              poi = new float[]{46.6711523f, 12.990682f};
              homographyMatrix[0][0] = -7.096579964937750f;
              homographyMatrix[0][1] = 4.434440730196080f;
              homographyMatrix[0][2] = 0.024682528830668f;
              homographyMatrix[1][0] = -34.054266572157481f;
              homographyMatrix[1][1] = -6.766623604111827f;
              homographyMatrix[1][2] = -0.029473116378125f;
              homographyMatrix[2][0] = 1672.332145242553500f;
              homographyMatrix[2][1] = 264.783328850081320f;
              homographyMatrix[2][2] = 1.000000000000000f;
            }

            if (augmentedImage.getIndex() == 41) {
              poi = new float[]{46.8322843f, 14.8161273f};
              homographyMatrix[0][0] = -12.527711504054990f;
              homographyMatrix[0][1] = -0.510887606585251f;
              homographyMatrix[0][2] = -0.000311185589090f;
              homographyMatrix[1][0] = -5.884466642723984f;
              homographyMatrix[1][1] = -8.752968316466943f;
              homographyMatrix[1][2] = -0.021707297652295f;
              homographyMatrix[2][0] = 447.193498274881160f;
              homographyMatrix[2][1] = 419.708377924174610f;
              homographyMatrix[2][2] = 1.000000000000000f;
            }

            if (augmentedImage.getIndex() == 42) {
              poi = new float[]{46.6711523f, 12.990682f};
              homographyMatrix[0][0] = -20.006759067469218f;
              homographyMatrix[0][1] = -1.114920854431756f;
              homographyMatrix[0][2] = -0.002002693371646f;
              homographyMatrix[1][0] = -3.820029299062024f;
              homographyMatrix[1][1] = -20.627020746269874f;
              homographyMatrix[1][2] = -0.021358426218235f;
              homographyMatrix[2][0] = 455.211696090637590f;
              homographyMatrix[2][1] = 979.997430002837060f;
              homographyMatrix[2][2] = 1.000000000000000f;
            }

            if (augmentedImage.getIndex() == 43) {
              poi = new float[]{46.8322843f, 14.8161273f};
              homographyMatrix[0][0] = -13.757890702156487f;
              homographyMatrix[0][1] = -2.873521469671798f;
              homographyMatrix[0][2] = -0.011649413181986f;
              homographyMatrix[1][0] = 6.171983900877823f;
              homographyMatrix[1][1] = -6.123854164165792f;
              homographyMatrix[1][2] = -0.018222823532991f;
              homographyMatrix[2][0] = -93.491329040771149f;
              homographyMatrix[2][1] = 332.076773697373880f;
              homographyMatrix[2][2] = 1.000000000000000f;
            }

            if (augmentedImage.getIndex() == 44) {
              poi = new float[]{46.6711523f, 12.990682f};
              homographyMatrix[0][0] = -7.973308863978692f;
              homographyMatrix[0][1] = 3.618513007483281f;
              homographyMatrix[0][2] = 0.019538576593413f;
              homographyMatrix[1][0] = -28.378142859212911f;
              homographyMatrix[1][1] = -7.176019446346772f;
              homographyMatrix[1][2] = -0.027925409937283f;
              homographyMatrix[2][0] = 1424.029860294992200f;
              homographyMatrix[2][1] = 292.654853513683120f;
              homographyMatrix[2][2] = 1.000000000000000f;
            }

            if (augmentedImage.getIndex() == 45) {
              poi = new float[]{46.8322843f, 14.8161273f};
              homographyMatrix[0][0] = -13.236813335553816f;
              homographyMatrix[0][1] = -0.258783927624079f;
              homographyMatrix[0][2] = 0.000737245652893f;
              homographyMatrix[1][0] = -6.917609700136417f;
              homographyMatrix[1][1] = -9.853222752030542f;
              homographyMatrix[1][2] = -0.022068016633783f;
              homographyMatrix[2][0] = 506.573688541291230f;
              homographyMatrix[2][1] = 467.899503021258510f;
              homographyMatrix[2][2] = 1.000000000000000f;
            }

            if (augmentedImage.getIndex() == 46) {
              poi = new float[]{46.6711523f, 12.990682f};
              homographyMatrix[0][0] = -18.112342639109823f;
              homographyMatrix[0][1] = -0.394767721427890f;
              homographyMatrix[0][2] = -0.001273365670021f;
              homographyMatrix[1][0] = -5.055270157252074f;
              homographyMatrix[1][1] = -18.380084877669546f;
              homographyMatrix[1][2] = -0.021545919685358f;
              homographyMatrix[2][0] = 488.548291875922360f;
              homographyMatrix[2][1] = 865.105421238483930f;
              homographyMatrix[2][2] = 1.000000000000000f;
            }

            if (augmentedImage.getIndex() == 47) {
              poi = new float[]{46.8322843f, 14.8161273f};
              homographyMatrix[0][0] = -13.777373376526777f;
              homographyMatrix[0][1] = -3.846771263545687f;
              homographyMatrix[0][2] = -0.011533327130797f;
              homographyMatrix[1][0] = 7.448699189367535f;
              homographyMatrix[1][1] = -6.127976695653342f;
              homographyMatrix[1][2] = -0.018240070608135f;
              homographyMatrix[2][0] = -150.484377094991170f;
              homographyMatrix[2][1] = 346.850195790858040f;
              homographyMatrix[2][2] = 1.000000000000000f;
            }

            if (augmentedImage.getIndex() == 48) {
              poi = new float[]{46.6711523f, 12.990682f};
              homographyMatrix[0][0] = -6.083680810104790f;
              homographyMatrix[0][1] = 4.735487851304796f;
              homographyMatrix[0][2] = 0.027784892941117f;
              homographyMatrix[1][0] = -37.756098212983140f;
              homographyMatrix[1][1] = -6.250253702831093f;
              homographyMatrix[1][2] = -0.030615951476339f;
              homographyMatrix[2][0] = 1830.762465404912300f;
              homographyMatrix[2][1] = 235.905666325051160f;
              homographyMatrix[2][2] = 1.000000000000000f;
            }

            if (augmentedImage.getIndex() == 49) {
              poi = new float[]{46.8322843f, 14.8161273f};
              homographyMatrix[0][0] = -12.824200802103370f;
              homographyMatrix[0][1] = -0.321525954817813f;
              homographyMatrix[0][2] = 0.000018873528203f;
              homographyMatrix[1][0] = -6.519369029037835f;
              homographyMatrix[1][1] = -9.151772970493859f;
              homographyMatrix[1][2] = -0.021823457394804f;
              homographyMatrix[2][0] = 484.108495767225290f;
              homographyMatrix[2][1] = 435.714821568064110f;
              homographyMatrix[2][2] = 1.000000000000000f;
            }

            if (augmentedImage.getIndex() == 50) {
              poi = new float[]{46.8322843f, 14.8161273f};
              homographyMatrix[0][0] = -17.166488052595671f;
              homographyMatrix[0][1] = -0.751218590169771f;
              homographyMatrix[0][2] = -0.000362764564283f;
              homographyMatrix[1][0] = -4.710812056399333f;
              homographyMatrix[1][1] = -17.027910356056307f;
              homographyMatrix[1][2] = -0.021801668717179f;
              homographyMatrix[2][0] = 462.278716460990950f;
              homographyMatrix[2][1] = 806.688801313285810f;
              homographyMatrix[2][2] = 1.000000000000000f;
            }

            if (augmentedImage.getIndex() == 51) {
              poi = new float[]{46.6711523f, 12.990682f};
              homographyMatrix[0][0] = -7.398516679104869f;
              homographyMatrix[0][1] = 4.232940120568550f;
              homographyMatrix[0][2] = 0.028359212125722f;
              homographyMatrix[1][0] = -36.831666752507743f;
              homographyMatrix[1][1] = -8.610366780836301f;
              homographyMatrix[1][2] = -0.030903829735541f;
              homographyMatrix[2][0] = 1807.257094817725100f;
              homographyMatrix[2][1] = 352.333954013382030f;
              homographyMatrix[2][2] = 1.000000000000000f;
            }

            if (augmentedImage.getIndex() == 52) {
              poi = new float[]{46.8322843f, 14.8161273f};
              homographyMatrix[0][0] = -12.765828486226988f;
              homographyMatrix[0][1] = 0.114571134181084f;
              homographyMatrix[0][2] = 0.001559010838895f;
              homographyMatrix[1][0] = -8.508774872011973f;
              homographyMatrix[1][1] = -9.525995147873196f;
              homographyMatrix[1][2] = -0.022323549045626f;
              homographyMatrix[2][0] = 576.803104254076520f;
              homographyMatrix[2][1] = 446.972573247700500f;
              homographyMatrix[2][2] = 1.000000000000000f;
            }

            if (augmentedImage.getIndex() == 53) {
              poi = new float[]{46.8322843f, 14.8161273f};
              homographyMatrix[0][0] = -22.059673779062159f;
              homographyMatrix[0][1] = -0.748287601993913f;
              homographyMatrix[0][2] = -0.002711927080456f;
              homographyMatrix[1][0] = -4.367978091207692f;
              homographyMatrix[1][1] = -22.670823035902995f;
              homographyMatrix[1][2] = -0.021161349526283f;
              homographyMatrix[2][0] = 519.127645648057410f;
              homographyMatrix[2][1] = 1069.699374797679400f;
              homographyMatrix[2][2] = 1.000000000000000f;
            }

            if (augmentedImage.getIndex() == 54) {
              poi = new float[]{46.6711523f, 12.990682f};
              homographyMatrix[0][0] = -6.352742777977047f;
              homographyMatrix[0][1] = 3.335926023740975f;
              homographyMatrix[0][2] = 0.020636156132276f;
              homographyMatrix[1][0] = -29.724452053305637f;
              homographyMatrix[1][1] = -4.897286126505484f;
              homographyMatrix[1][2] = -0.028448127544192f;
              homographyMatrix[2][0] = 1465.905508503544100f;
              homographyMatrix[2][1] = 189.212008715435590f;
              homographyMatrix[2][2] = 1.000000000000000f;
            }

            if (augmentedImage.getIndex() == 55) {
              poi = new float[]{46.8322843f, 14.8161273f};
              homographyMatrix[0][0] = -12.141099927394290f;
              homographyMatrix[0][1] = 0.770182172902419f;
              homographyMatrix[0][2] = 0.003786495720119f;
              homographyMatrix[1][0] = -11.027181579734815f;
              homographyMatrix[1][1] = -9.545544406189091f;
              homographyMatrix[1][2] = -0.023040423085897f;
              homographyMatrix[2][0] = 686.073813283944790f;
              homographyMatrix[2][1] = 438.131369533742090f;
              homographyMatrix[2][2] = 1.000000000000000f;
            }

            if (augmentedImage.getIndex() == 56) {
              poi = new float[]{46.8322843f, 14.8161273f};
              homographyMatrix[0][0] = -23.187900245511567f;
              homographyMatrix[0][1] = -0.066527362755045f;
              homographyMatrix[0][2] = -0.000225974247741f;
              homographyMatrix[1][0] = -6.794511741705041f;
              homographyMatrix[1][1] = -25.233628295928096f;
              homographyMatrix[1][2] = -0.021991016706286f;
              homographyMatrix[2][0] = 650.414350337643210f;
              homographyMatrix[2][1] = 1178.888783838135600f;
              homographyMatrix[2][2] = 1.000000000000000f;
            }

            if (augmentedImage.getIndex() == 57) {
              poi = new float[]{46.6711523f, 12.990682f};
              homographyMatrix[0][0] = -6.339934077205157f;
              homographyMatrix[0][1] = 2.555840345431050f;
              homographyMatrix[0][2] = 0.026181745857695f;
              homographyMatrix[1][0] = -33.788112727325412f;
              homographyMatrix[1][1] = -6.407772626438725f;
              homographyMatrix[1][2] = -0.030320670599155f;
              homographyMatrix[2][0] = 1653.043834829698900f;
              homographyMatrix[2][1] = 272.468859657092030f;
              homographyMatrix[2][2] = 1.000000000000000f;
            }

            if (augmentedImage.getIndex() == 58) {
              poi = new float[]{46.8322843f, 14.8161273f};
              homographyMatrix[0][0] = -11.811595393746858f;
              homographyMatrix[0][1] = 1.473764023170139f;
              homographyMatrix[0][2] = 0.005446162529675f;
              homographyMatrix[1][0] = -13.526049184432507f;
              homographyMatrix[1][1] = -9.743975834358446f;
              homographyMatrix[1][2] = -0.023600491923060f;
              homographyMatrix[2][0] = 797.934135533212500f;
              homographyMatrix[2][1] = 437.143887838658260f;
              homographyMatrix[2][2] = 1.000000000000000f;
            }

            if (augmentedImage.getIndex() == 59) {
              poi = new float[]{46.8322843f, 14.8161273f};
              homographyMatrix[0][0] = -23.122498125921755f;
              homographyMatrix[0][1] = -1.012389357229551f;
              homographyMatrix[0][2] = 0.003100283975120f;
              homographyMatrix[1][0] = -6.392740714481308f;
              homographyMatrix[1][1] = -27.480894469235331f;
              homographyMatrix[1][2] = -0.023091788174903f;
              homographyMatrix[2][0] = 632.731141530845090f;
              homographyMatrix[2][1] = 1297.516279496676800f;
              homographyMatrix[2][2] = 1.000000000000000f;
            }

            if (augmentedImage.getIndex() == 60) {
              poi = new float[]{46.6711523f, 12.990682f};
              homographyMatrix[0][0] = -5.491298129471942f;
              homographyMatrix[0][1] = 3.781068649942659f;
              homographyMatrix[0][2] = 0.035734801504642f;
              homographyMatrix[1][0] = -43.467188874695751f;
              homographyMatrix[1][1] = -7.799892641410172f;
              homographyMatrix[1][2] = -0.033569705896029f;
              homographyMatrix[2][0] = 2087.495556107704900f;
              homographyMatrix[2][1] = 321.850747664408400f;
              homographyMatrix[2][2] = 1.000000000000000f;
            }

            if (augmentedImage.getIndex() == 61) {
              poi = new float[]{46.8322843f, 14.8161273f};
              homographyMatrix[0][0] = -11.563191829375524f;
              homographyMatrix[0][1] = 2.294736092107061f;
              homographyMatrix[0][2] = 0.008121168930746f;
              homographyMatrix[1][0] = -16.842238570051052f;
              homographyMatrix[1][1] = -10.018050659732889f;
              homographyMatrix[1][2] = -0.024508250366225f;
              homographyMatrix[2][0] = 948.584492472377290f;
              homographyMatrix[2][1] = 438.190268384192450f;
              homographyMatrix[2][2] = 1.000000000000000f;
            }

            if (augmentedImage.getIndex() == 62) {
              poi = new float[]{46.8322843f, 14.8161273f};
              homographyMatrix[0][0] = -27.866247158360121f;
              homographyMatrix[0][1] = -0.112487532819064f;
              homographyMatrix[0][2] = -0.000316270317643f;
              homographyMatrix[1][0] = -6.569756830171846f;
              homographyMatrix[1][1] = -32.570559036493428f;
              homographyMatrix[1][2] = -0.022076696195705f;
              homographyMatrix[2][0] = 713.905179038452390f;
              homographyMatrix[2][1] = 1520.541931721819300f;
              homographyMatrix[2][2] = 1.000000000000000f;
            }


            tempX = homographyMatrix[0][0] * poi[1] + homographyMatrix[1][0] * poi[0] + homographyMatrix[2][0];
            tempY = homographyMatrix[0][1] * poi[1] + homographyMatrix[1][1] * poi[0] + homographyMatrix[2][1];

            placeOfInterestX = (int) (tempX / (homographyMatrix[0][2] * poi[1] + homographyMatrix[1][2] * poi[0] + homographyMatrix[2][2]));
            placeOfInterestY = (int) (tempY / (homographyMatrix[0][2] * poi[1] + homographyMatrix[1][2] * poi[0] + homographyMatrix[2][2]));
            placeOfInterestY = placeOfInterestY * -1;

            //Log.i("Coordinates " + String.valueOf(augmentedImage.getIndex()), String.valueOf(placeOfInterestX) + "  " + String.valueOf(placeOfInterestY));

            aiWidth = augmentedImage.getExtentX();
            aiHeight = augmentedImage.getExtentZ();

            //Log.i("Measures " + String.valueOf(augmentedImage.getIndex()), String.valueOf(aiWidth) + "  " + String.valueOf(aiHeight));

            //640x480
            //320x240

            //get the delta -> from center position
            deltaX = 320 - placeOfInterestX;
            deltaY = 240 - placeOfInterestY;

            //delta to %
            deltaPercentX = (deltaX * 10) / 64;
            deltaPercentY = (deltaY * 10) / 48;

            //calculatoe to real values
            realX = -(deltaPercentX * aiWidth) / 100;
            realY = (deltaPercentY * aiHeight) / 100;
            //Log.i("PercentTransformation " + String.valueOf(augmentedImage.getIndex()), String.valueOf(deltaPercentX) + "  " + String.valueOf(deltaPercentY));
            //Log.i("RealTransformation " + String.valueOf(augmentedImage.getIndex()), String.valueOf(realX) + "  " + String.valueOf(realY));
            float[] translation = {(float) realX, (float) realY, 0f};
            float[] rotation = {0, 0, 0, 0};
            transformPose = new Pose(translation, rotation).extractTranslation();
            center_Pose = augmentedImage.getCenterPose();
            //Log.i("Pose " + String.valueOf(augmentedImage.getIndex()), center_Pose.toString());
            //center_Pose=center_Pose.compose(transformPose);
            center_Pose = transformPose.compose(center_Pose);
              //center_Pose
            //Anchor centerPoseAnchor = augmentedImage.createAnchor(augmentedImage.getCenterPose());
            //augmentedImageMap.clear();
//            augmentedImageMap.put(
//           augmentedImage.getIndex(), Pair.create(augmentedImage, centerPoseAnchor));
            //augmentedImageMap.clear();

            // Create a new anchor for newly found images.
            if (!augmentedImageMap.containsKey(augmentedImage.getIndex())) {
              Anchor centerPoseAnchorPOI = augmentedImage.createAnchor(center_Pose);
              augmentedImageMap.put(
                      augmentedImage.getIndex(), Pair.create(augmentedImage, centerPoseAnchorPOI));
              //Log.i("ImageMapCount", String.valueOf(augmentedImageMap.keySet()));
            }
            else
            {
              // Compute the difference vector between the two Poses
              Pose storedPose = augmentedImageMap.get(augmentedImage.getIndex()).second.getPose();

              float dx = storedPose.tx() - center_Pose.tx();
              float dy = storedPose.ty() - center_Pose.ty();
              float dz = storedPose.tz() - center_Pose.tz();

              // Compute the straight-line distance
              float distanceMeters = (float) Math.sqrt(dx*dx + dy*dy + dz*dz);
              //Log.i("Offset", String.valueOf(distanceMeters));
              if (distanceMeters> 0.03){
                Anchor centerPoseAnchorPOI = augmentedImage.createAnchor(center_Pose);
                augmentedImageMap.put(
                        augmentedImage.getIndex(), Pair.create(augmentedImage, centerPoseAnchorPOI));
              }
            }
          }
            break;

            default:
              augmentedImageMap.remove(augmentedImage.getIndex());
              break;
          }
      }




    // Draw all images in augmentedImageMap
    for (Pair<AugmentedImage, Anchor> pair : augmentedImageMap.values()) {
      AugmentedImage augmentedImage = pair.first;
      Anchor centerAnchor = augmentedImageMap.get(augmentedImage.getIndex()).second;
      switch (augmentedImage.getTrackingState()) {
        case TRACKING:
          if (augmentedImage.getTrackingMethod() == AugmentedImage.TrackingMethod.FULL_TRACKING) {
            augmentedImageRenderer.draw(
                    viewmtx, projmtx, augmentedImage, centerAnchor, colorCorrectionRgba);
          }
//          else{
//            Log.i("NotFull", String.valueOf(augmentedImage.getIndex()));
//            augmentedImageMap.remove(augmentedImage.getIndex());
//          }

          break;
        default:
          //augmentedImageMap.remove(augmentedImage.getIndex());
          break;
      }
//      if (augmentedImage.getTrackingMethod() != AugmentedImage.TrackingMethod.FULL_TRACKING) {
//        augmentedImageMap.remove(augmentedImage.getIndex());
//      }

    }

//          if (augmentedImage.getTrackingMethod() != AugmentedImage.TrackingMethod.FULL_TRACKING) {
//        augmentedImageMap.remove(augmentedImage.getIndex());
//      }


//        for (int index : augmentedImageMap.keySet()){
//      if (Arrays.stream(updatedAugmentedImagesIndexes).anyMatch(num -> num==index)){
//        continue;
//      }else{
//        augmentedImageMap.remove(index);
//      }
//    }

  // delete all not full tracking
    for (Pair<AugmentedImage, Anchor> pair : augmentedImageMap.values()) {
      AugmentedImage augmentedImage = pair.first;
      if (augmentedImage.getTrackingMethod() != AugmentedImage.TrackingMethod.FULL_TRACKING) {
        augmentedImageMap.remove(augmentedImage.getIndex());
      }
    }

   // https://developers.google.com/ar/develop/java/augmented-images/guide
//    Note: If an image in the camera view changes very rapidly to a second image that has roughly
//    the same size and position, ARCore may erroneously set the TrackingMethod to FULL_TRACKING
//  for both images and also update the anchor of the first Augmented Image to the position of the
//  new image. For example, this could happen if you were pointing the camera at an image in a web
//  browser, and then switched to another browser tab containing a second similarly sized and
//    positioned image.
  }

  private boolean setupAugmentedImageDatabase(Config config) {
    AugmentedImageDatabase augmentedImageDatabase;
      try (InputStream is = getAssets().open("subset.imgdb")) {
        augmentedImageDatabase = AugmentedImageDatabase.deserialize(session, is);
      } catch (IOException e) {
        Log.e(TAG, "IO exception loading augmented image database.", e);
        return false;
      }
    //Log.i("Number", String.valueOf(augmentedImageDatabase.getNumImages()));
    config.setAugmentedImageDatabase(augmentedImageDatabase);
    return true;
  }
}
