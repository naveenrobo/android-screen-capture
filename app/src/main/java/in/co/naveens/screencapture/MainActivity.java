package in.co.naveens.screencapture;


import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.hardware.display.VirtualDisplay;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.Surface;
import android.view.View;
import android.widget.Button;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.ByteBuffer;


/**
 * A simple launcher activity containing a summary sample description, sample log and a custom
 * {@link androidx.fragment.app.Fragment} which can display a view.
 * <p>
 * For devices with displays with a width of 720dp or greater, the sample log is always visible,
 * on other devices it's visibility is controlled by an item on the Action Bar.
 */
public class MainActivity extends Activity {

    private static final String TAG = MainActivity.class.getSimpleName();

    private Button cast;
    private TcpSocketClient client;
    private boolean muxerStarted;
    private MediaProjectionManager mediaProjectionManager;
    private MediaProjection mediaProjection;

    private Surface inputSurface;
    private VirtualDisplay virtualDisplay;
    private MediaCodec.BufferInfo videoBufferInfo;
    private MediaCodec encoder;
    private MediaCodec.Callback encoderCallback;
    private final String FORMAT = "video/avc";

    private static final int REQUEST_CODE_CAPTURE_PERM = 1234;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.tcp_layout);

        /* Check the version. should be greater than M */
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            new AlertDialog.Builder(this)
                    .setTitle("Error")
                    .setMessage("This activity only works on Marshmallow or later.")
                    .setNeutralButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            Log.d(TAG, "Closing");
                        }
                    })
                    .show();
            return;
        }

        try {
            client = new TcpSocketClient(InetAddress.getByName("192.168.1.168"), 49152);
            client.start();
            Log.d(TAG, "Socket connected");
        } catch (Exception e) {
            e.printStackTrace();
        }

        mediaProjectionManager = (MediaProjectionManager) getSystemService(android.content.Context.MEDIA_PROJECTION_SERVICE);

        cast = findViewById(R.id.cast_screen);
        cast.setText("Start casting");
        cast.setOnClickListener(new View.OnClickListener() {
            @RequiresApi(api = Build.VERSION_CODES.M)
            @Override
            public void onClick(View v) {
                if (muxerStarted) {
                    Log.d(TAG, "Stopping casting");
                    stopScreenCapture();
                    cast.setText("Start casting");
                } else {
                    Log.d(TAG, "Starting the cast");
                    cast.setText("Initializing");
                    Intent permissionIntent = mediaProjectionManager.createScreenCaptureIntent();
                    startActivityForResult(permissionIntent, REQUEST_CODE_CAPTURE_PERM);
                    cast.setEnabled(false);
                }
            }
        });


        encoderCallback = new MediaCodec.Callback() {
            @Override
            public void onInputBufferAvailable(MediaCodec codec, int inputBufferId) {
            }

            @Override
            public void onOutputBufferAvailable(MediaCodec codec, int outputBufferId, MediaCodec.BufferInfo info) {
                ByteBuffer outputBuffer = codec.getOutputBuffer(outputBufferId);
                if (info.size > 0 && outputBuffer != null && muxerStarted) {
                    outputBuffer.position(info.offset);
                    outputBuffer.limit(info.offset + info.size);
                    byte[] b = new byte[outputBuffer.remaining()];
                    outputBuffer.get(b);
                    sendData(null, b);
                }
                if (encoder != null) {
                    encoder.releaseOutputBuffer(outputBufferId, false);
                }
                if ((videoBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    Log.i(TAG, "End of Stream");
                    stopScreenCapture();
                }
            }

            @Override
            public void onError(MediaCodec codec, MediaCodec.CodecException e) {
                e.printStackTrace();
            }

            @Override
            public void onOutputFormatChanged(MediaCodec codec, MediaFormat format) {
                if (!muxerStarted) {
                    muxerStarted = true;
                }
                Log.i(TAG, "onOutputFormatChanged. CodecInfo:" + codec.getCodecInfo().toString() + " MediaFormat:" + format.toString());
            }
        };
    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    private void startCasting(int width, int height) {
        MediaFormat format = MediaFormat.createVideoFormat(FORMAT, width, height);
        int frameRate = 60; // 30 fps
        int dpi = 96;

        videoBufferInfo = new MediaCodec.BufferInfo();

        // Set some required properties. The media codec may fail if these aren't defined.
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
//        format.setInteger(MediaFormat.KEY_BIT_RATE, 6000000); // 6Mbps
//        format.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate);
//        format.setInteger(MediaFormat.KEY_CAPTURE_RATE, frameRate);
//        format.setInteger(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 1000000 / frameRate);
//        format.setInteger(MediaFormat.KEY_CHANNEL_COUNT, 1);
//        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1); // 1 seconds between I-frames

        format.setInteger(MediaFormat.KEY_BIT_RATE, 1024000);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate);
        format.setInteger(MediaFormat.KEY_CHANNEL_COUNT, 0);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);

        // Create a MediaCodec encoder and configure it. Get a Surface we can use for recording into.
        try {
            encoder = MediaCodec.createEncoderByType(FORMAT);
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            inputSurface = encoder.createInputSurface();
            encoder.setCallback(encoderCallback);
            encoder.start();
            mediaProjection.createVirtualDisplay("Recording Display", width, height, dpi, 0, this.inputSurface, null, null);
        } catch (IOException e) {
            Log.e(TAG, "Failed to initial encoder, e: " + e);
            releaseEncoders();
        }
    }

    private void stopScreenCapture() {
        Log.d(TAG, "Stopping screen casting");
        releaseEncoders();
        if (virtualDisplay == null) {
            return;
        }
        virtualDisplay.release();
        virtualDisplay = null;

    }

    private void releaseEncoders() {
        Log.d(TAG, "Releasing encoders");
        muxerStarted = false;
        if (encoder != null) {
            encoder.stop();
            encoder.release();
            encoder = null;
        }
        if (inputSurface != null) {
            inputSurface.release();
            inputSurface = null;
        }
        if (mediaProjection != null) {
            mediaProjection.stop();
            mediaProjection = null;
        }
        videoBufferInfo = null;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        Log.d(TAG, "result =" + resultCode);
        if (REQUEST_CODE_CAPTURE_PERM == requestCode) {
            cast.setEnabled(true);
            if (resultCode == RESULT_OK) {
                mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data);
                startCasting(640, 360);
                cast.setText("Stop casting");
            } else {
                new AlertDialog.Builder(this)
                        .setTitle("Error")
                        .setMessage("Permission is required to record the screen.")
                        .setNeutralButton(android.R.string.ok, null)
                        .show();
            }
        }
    }

    /* Part where TCP IP plays a role */
    private void sendData(byte[] header, byte[] data) {
        if (client != null) {
            if (header != null) {
                client.send(header);
            }
            client.send(data);
        } else {
            Log.e(TAG, "Both tcp and udp socket are not available.");
            stopScreenCapture();
        }
    }

    private void closeSocket() {
        if (client != null) {
            try {
                client.close();
            } catch (Exception ex) {
                ex.printStackTrace();
            } finally {
                client = null;
            }
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "onDestroy");
        stopScreenCapture();
        closeSocket();
    }

}

