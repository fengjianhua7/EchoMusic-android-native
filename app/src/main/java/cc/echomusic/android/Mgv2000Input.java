package cc.echomusic.android;

import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import java.util.function.Consumer;

final class Mgv2000Input {
    private Mgv2000Input() {}

    static Bitmap qr(String value) throws Exception {
        int size = 384;
        BitMatrix matrix = new QRCodeWriter().encode(value, BarcodeFormat.QR_CODE, size, size);
        int[] pixels = new int[size * size];
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                pixels[y * size + x] = matrix.get(x, y) ? Color.BLACK : Color.WHITE;
            }
        }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888);
    }

    static void open(MainActivity activity, EditText field, boolean address) {
        LinearLayout body = activity.col();
        activity.pad(body, 12);
        StringBuilder draft = new StringBuilder(field.getText());
        TextView preview = activity.text(draft.toString(), 18, Color.BLACK);
        preview.setSingleLine(true);
        preview.setEllipsize(android.text.TextUtils.TruncateAt.START);
        body.addView(preview, activity.lp(-1, 42));
        Consumer<String> update = key -> {
            if (key.equals("退格")) {
                if (draft.length() > 0) draft.delete(draft.offsetByCodePoints(draft.length(), -1), draft.length());
            } else if (key.equals("清空")) {
                draft.setLength(0);
            } else if (draft.length() + key.length() <= 512) {
                draft.append(key);
            }
            preview.setText(draft.toString());
        };
        String[] rows = {"1234567890", "qwertyuiop", "asdfghjkl", "zxcvbnm.-_",
                         ":/@%+[]"};
        for (String keys : rows) {
            LinearLayout row = activity.row();
            for (int i = 0; i < keys.length(); i++) {
                String key = keys.substring(i, i + 1);
                TextView button = activity.button(key, () -> update.accept(key));
                button.setPadding(0, 0, 0, 0);
                row.addView(button, new LinearLayout.LayoutParams(0, activity.dp(48), 1));
            }
            body.addView(row);
        }
        LinearLayout actions = activity.row();
        if (address) actions.addView(activity.button("http://", () -> update.accept("http://")));
        else actions.addView(activity.button("空格", () -> update.accept(" ")));
        actions.addView(activity.button("退格", () -> update.accept("退格")));
        actions.addView(activity.button("清空", () -> update.accept("清空")));
        body.addView(actions);
        AlertDialog dialog = new AlertDialog.Builder(activity)
            .setTitle(address ? "输入音乐服务地址" : "输入搜索词")
            .setView(body).setNegativeButton("取消", null)
            .setPositiveButton("完成", (d, w) -> {
                field.setText(draft.toString());
                field.setSelection(field.length());
            }).create();
        dialog.show();
        TvFocus.install(dialog.getWindow().getDecorView());
    }
}
