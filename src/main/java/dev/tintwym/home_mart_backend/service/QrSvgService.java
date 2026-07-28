package dev.tintwym.home_mart_backend.service;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class QrSvgService {

    public String toSvg(String content) {
        return toSvg(content, 200);
    }

    public String toSvg(String content, int size) {
        try {
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
            hints.put(EncodeHintType.MARGIN, 1);

            BitMatrix matrix = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints);
            int width = matrix.getWidth();
            int height = matrix.getHeight();

            StringBuilder sb = new StringBuilder();
            sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 ")
                    .append(width)
                    .append(' ')
                    .append(height)
                    .append("\" width=\"")
                    .append(size)
                    .append("\" height=\"")
                    .append(size)
                    .append("\" shape-rendering=\"crispEdges\">");
            sb.append("<rect width=\"100%\" height=\"100%\" fill=\"#ffffff\"/>");
            sb.append("<path fill=\"#000000\" d=\"");

            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    if (matrix.get(x, y)) {
                        sb.append('M').append(x).append(',').append(y)
                                .append("h1v1h-1z");
                    }
                }
            }
            sb.append("\"/></svg>");
            return sb.toString();
        } catch (WriterException e) {
            throw new IllegalStateException("Failed to generate QR SVG", e);
        }
    }
}
