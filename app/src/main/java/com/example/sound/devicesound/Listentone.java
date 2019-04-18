package com.example.sound.devicesound;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;

import org.apache.commons.math3.complex.Complex;
import org.apache.commons.math3.transform.*;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;

import calsualcoding.reedsolomon.EncoderDecoder;

public class Listentone {

    int HANDSHAKE_START_HZ = 4096;
    int HANDSHAKE_END_HZ = 5120 + 1024;

    int START_HZ = 1024;
    int STEP_HZ = 256;
    int BITS = 4;

    int FEC_BYTES = 4;

    private int mAudioSource = MediaRecorder.AudioSource.MIC;
    private int mSampleRate = 44100;
    private int mChannelCount = AudioFormat.CHANNEL_IN_MONO;
    private int mAudioFormat = AudioFormat.ENCODING_PCM_16BIT;
    private float interval = 0.1f;

    private int mBufferSize = AudioRecord.getMinBufferSize(mSampleRate, mChannelCount, mAudioFormat);

    public AudioRecord mAudioRecord = null;
    int audioEncodig;
    boolean startFlag;
    FastFourierTransformer transform;


    public Listentone() {

        transform = new FastFourierTransformer(DftNormalization.STANDARD);
        startFlag = false;
        mAudioRecord = new AudioRecord(mAudioSource, mSampleRate, mChannelCount, mAudioFormat, mBufferSize);
        mAudioRecord.startRecording();

    }


    public void PreRequest() {
        ///////// 채우기
        int blocksize = findPowerSize((int) (long) Math.round(interval / 2 * mSampleRate));
        short[] buffer = new short[blocksize];
        System.out.println("Start.....");

        boolean in_packet = false;
        double dom = 0;
        ArrayList<Double> packets = new ArrayList<>();

        while (true) {
            int bufferedReadResult = mAudioRecord.read(buffer, 0, blocksize);    //2048
            double[] aBuffer = new double[buffer.length];

            if (bufferedReadResult == 2048) {
                for (int i = 0; i < buffer.length; i++) {
                    aBuffer[i] = buffer[i];
                }

                dom = findFrequency(aBuffer);
                //Log.d("DDDDOOOMMM : ", Double.toString(dom));

                if (in_packet && match(dom, HANDSHAKE_END_HZ)) {
                    ArrayList<Byte> byte_stream;
                    //byte[] byte_stream2;
                    byte_stream = extract_packet(packets);

                    //EncoderDecoder decoder = new EncoderDecoder();

                    try {
                        byte[] real = new byte[byte_stream.size()];
                        for (int i = 0; i < byte_stream.size(); i++) {
                            real[i] = byte_stream.get(i);

                        }
                        //byte_stream2 = decoder.decodeData(real, 0);

                        String Result = new String(real, "UTF-8");
                        Log.d("[message]", Result);

                    } catch (Exception e) {
                        e.printStackTrace();
                    }

                } else if (in_packet) {
                    packets.add(dom);

                } else if (match(dom, HANDSHAKE_START_HZ)) {
                    Log.d("[######]", "HANDSHAKE Start!");
                    in_packet = true;
                }
            }
        }


    }


    private double findFrequency(double[] toTransform) {
        int len = toTransform.length;
        double[] real = new double[len];
        double[] img = new double[len];
        double realNum;
        double imgNum;
        double[] mag = new double[len];
        double fin = 0;

        Complex[] complx = transform.transform(toTransform, TransformType.FORWARD);
        // Complex : 정수 + 허수 i 형태로 만들 수 있음
        Double[] freq = this.fftfreq(complx.length, 1);
        // 주파수를 가지고 온다

        for (int i = 0; i < complx.length; i++) {
            realNum = complx[i].getReal();
            imgNum = complx[i].getImaginary();
            mag[i] = Math.sqrt((realNum * realNum) + (imgNum * imgNum));
            // 푸리에 트랜스폼 결과 :  ( !!! 강도 !!! ) amplitude
        }
        // 주기함수 = 주파수와  amplitude로 이루어져있고
        // 푸리에 변환 = 주기함수들을 다 합친 것

        double magValueMax = mag[0];
        int maxIndex = 0;
        for (int i = 1; i < complx.length; i++) {
            if (magValueMax < mag[i]) {
                magValueMax = mag[i];
                maxIndex = i;
            }
        }
        double peak_freq = freq[maxIndex];
        return Math.abs(44100 * peak_freq);
    }

    private int findPowerSize(int size) {
        double i = 0;
        double b, prev, next, fin;
        double a = Math.pow(2, i);
        while (size > a) {
            i++;
            a = Math.pow(2, i);
        }
        b = a / 2;
        next = a - size;
        prev = size - b;
        if (prev < next) {
            fin = b;
        } else {
            fin = a;
        }
        return (int) fin;
    }

    private Double[] fftfreq(int length, double y) {
        Double[] freq = new Double[length];
        if (length % 2 == 0) {
            int index = 0;
            for (int i = 0; i <= (length / 2) - 1; i++) {
                freq[index++] = i * (y / length);
            }
            for (int i = -length / 2; i <= -1; i++) {
                freq[index++] = i * (y / length);
            }
        } else {
            int index = 0;
            for (int i = 0; i <= (length - 1) / 2; i++) {
                freq[index++] = i * (y / length);
            }
            for (int i = -(length - 1) / 2; i <= -1; i++) {
                freq[index++] = i * (y / length);
            }
        }

        return freq;
    }

    private ArrayList<Byte> extract_packet(ArrayList<Double> freqs) {
        ArrayList<Integer> bit_chunks = new ArrayList<>();
        ArrayList<Integer> bit_chunks2 = new ArrayList<>();

        for (int i = 0; i < freqs.size(); i = i + 2) {
            bit_chunks.add((int) Math.round((freqs.get(i) - START_HZ) / STEP_HZ));
        }

        for (int i = 1; i < bit_chunks.size(); i++) {
            int a = bit_chunks.get(i);

            if (a >= 0 && (a < Math.pow(2, (double) BITS))) {
                bit_chunks2.add(a);
            }
        }

        ArrayList<Integer> endByte;
        endByte = decode_bitchunks(BITS, bit_chunks2);

        ArrayList<Byte> final_bytes = new ArrayList<>();
        for (int i = 0; i < endByte.size(); i++) {
            byte[] endbytes;
            endbytes = intToByteArray(endByte.get(i).intValue());

            for (int j = 0; j < endbytes.length; j++) {
                if ((int) endbytes[j] != 0) {
                    final_bytes.add(endbytes[j]);
                }
            }
        }

        return final_bytes;
    }

    private byte[] intToByteArray(int value) {
        byte[] bytes = new byte[4];

        bytes[0] |= (byte) ((value & 0xFF000000) >> 24) & 0xFF;
        bytes[1] |= (byte) ((value & 0x00FF0000) >> 16) & 0xFF;
        bytes[2] |= (byte) ((value & 0x0000FF00) >> 8) & 0xFF;
        bytes[3] |= (byte) (value & 0x000000FF) & 0xFF;

        return bytes;
    }

    private ArrayList<Integer> decode_bitchunks(int bits, ArrayList<Integer> bit_chunks) {
        ArrayList<Integer> bitArray = new ArrayList<>();
        int next_read_chunk = 0;
        int next_read_bit = 0;

        int byte_1 = 0;
        int bits_left = 8;

        while (next_read_chunk < bit_chunks.size()) {
            int can_fill = bits - next_read_bit;
            int to_fill = Math.min(bits_left, can_fill);
            int offset = bits - next_read_bit - to_fill;
            byte_1 <<= to_fill;
            int shifted = bit_chunks.get(next_read_chunk) & (((1 << to_fill) - 1) << offset);
            byte_1 |= shifted >> offset;
            bits_left -= to_fill;
            next_read_bit += to_fill;
            if (bits_left <= 0) {
                bitArray.add(byte_1);
                byte_1 = 0;
                bits_left = 8;
            }
            if (next_read_bit >= bits) {
                next_read_chunk += 1;
                next_read_bit -= bits;
            }
        }

        return bitArray;
    }


    private boolean match(double freq1, double freq2) {
        return (Math.abs(freq1 - freq2) < 20);
    }

}
