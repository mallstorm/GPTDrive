package com.gptdrive.app;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

final class AppLockManager {
    static final String BOTH="both", BIOMETRIC="biometric", PATTERN="pattern";
    private static final String PREF="app_lock_v1";
    private static volatile boolean unlocked;
    private static volatile long backgroundAt;

    private static SharedPreferences prefs(Context c){ return c.getSharedPreferences(PREF, Context.MODE_PRIVATE); }
    static boolean enabled(Context c){ return prefs(c).getBoolean("enabled", false); }
    static void setEnabled(Context c, boolean v){ prefs(c).edit().putBoolean("enabled",v).apply(); unlocked=!v; }
    static String mode(Context c){ return prefs(c).getString("mode", BOTH); }
    static void setMode(Context c,String v){ prefs(c).edit().putString("mode",v).apply(); }
    static long timeout(Context c){ return prefs(c).getLong("timeout",30000L); }
    static void setTimeout(Context c,long v){ prefs(c).edit().putLong("timeout",v).apply(); }
    static boolean hasPattern(Context c){ return !prefs(c).getString("hash","").isEmpty(); }
    static boolean allowPattern(Context c){ return PATTERN.equals(mode(c)) || BOTH.equals(mode(c)); }
    static boolean allowBiometric(Context c){ return BIOMETRIC.equals(mode(c)) || BOTH.equals(mode(c)); }
    static void unlock(){ unlocked=true; backgroundAt=0; }
    static boolean needsUnlock(Context c){ return enabled(c) && !unlocked; }
    static void onBackground(){ backgroundAt=SystemClock.elapsedRealtime(); }
    static void onForeground(Context c){
        if(!enabled(c)){ unlocked=true; return; }
        long t=timeout(c);
        if(backgroundAt>0 && (t==0 || SystemClock.elapsedRealtime()-backgroundAt>=t)) unlocked=false;
        backgroundAt=0;
    }
    /** Persistent brute-force guard: delay doubles after every 5 wrong attempts (30 s … 1 h). */
    static long blockedUntil(Context c){ return prefs(c).getLong("blocked_until", 0L); }
    static void registerFailure(Context c){
        SharedPreferences p=prefs(c);
        int fails=p.getInt("fails",0)+1;
        SharedPreferences.Editor e=p.edit().putInt("fails",fails);
        if(fails%5==0){
            int round=Math.min(7,fails/5-1);
            long delay=Math.min(3_600_000L,30_000L<<round);
            e.putLong("blocked_until",System.currentTimeMillis()+delay);
        }
        e.apply();
    }
    static void resetFailures(Context c){ prefs(c).edit().putInt("fails",0).putLong("blocked_until",0L).apply(); }
    static void setPattern(Context c,int[] pattern){
        byte[] salt=new byte[16]; new SecureRandom().nextBytes(salt);
        byte[] hash=derive(join(pattern).toCharArray(),salt);
        prefs(c).edit().putString("salt",hex(salt)).putString("hash",hex(hash)).apply();
        Arrays.fill(hash,(byte)0);
    }
    static boolean verify(Context c,int[] pattern){
        try{
            byte[] salt=unhex(prefs(c).getString("salt",""));
            byte[] expected=unhex(prefs(c).getString("hash",""));
            byte[] actual=derive(join(pattern).toCharArray(),salt);
            boolean ok=MessageDigest.isEqual(expected,actual);
            Arrays.fill(actual,(byte)0); return ok;
        }catch(Exception e){ return false; }
    }
    private static byte[] derive(char[] value,byte[] salt){
        try{return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(new PBEKeySpec(value,salt,120000,256)).getEncoded();}
        catch(Exception e){throw new IllegalStateException(e);}
    }
    private static String join(int[] p){StringBuilder s=new StringBuilder();for(int v:p)s.append((char)('0'+v));return s.toString();}
    private static String hex(byte[] b){StringBuilder s=new StringBuilder();for(byte x:b)s.append(String.format("%02x",x));return s.toString();}
    private static byte[] unhex(String s){byte[] b=new byte[s.length()/2];for(int i=0;i<b.length;i++)b[i]=(byte)Integer.parseInt(s.substring(i*2,i*2+2),16);return b;}
}