package com.gptdrive.app;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.widget.TextView;

/** Compact deterministic account identity used in the header and account list. */
final class AccountBadgeView extends TextView {
    AccountBadgeView(Context context) {
        super(context);
        setGravity(Gravity.CENTER);
        setTextColor(Color.WHITE);
        setTextSize(11);
        setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        setSingleLine(true);
    }

    void bind(AccountProfile account) {
        if (account == null) {
            setText("?");
            setBackground(circle(0xFF98A2B3));
            return;
        }
        setText(initials(account));
        setBackground(circle(colorFor(account)));
        setContentDescription("Аккаунт " + account.displayName());
    }

    static int colorFor(AccountProfile account) {
        String key = account == null ? "" : ((account.email == null ? "" : account.email) + "#" + account.id);
        int[] palette = {0xFF0F9D78, 0xFF3478F6, 0xFF7957E8, 0xFFE06B36, 0xFFB54708, 0xFF0E9384, 0xFF7A5AF8, 0xFFD92D20};
        return palette[Math.floorMod(key.hashCode(), palette.length)];
    }

    static String initials(AccountProfile account) {
        if (account == null) return "?";
        String value = account.email != null && !account.email.trim().isEmpty() ? account.email : account.displayName();
        if (value == null) value = "";
        value = value.trim();
        int at = value.indexOf('@');
        if (at > 0) value = value.substring(0, at);
        String[] words = value.split("[._\\-\\s]+");
        StringBuilder out = new StringBuilder();
        for (String word : words) {
            if (!word.isEmpty()) out.append(Character.toUpperCase(word.charAt(0)));
            if (out.length() == 2) break;
        }
        if (out.length() == 0 && !value.isEmpty()) out.append(Character.toUpperCase(value.charAt(0)));
        if (out.length() == 0) out.append(account.id);
        return out.length() > 2 ? out.substring(0, 2) : out.toString();
    }

    private GradientDrawable circle(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        return g;
    }
}
