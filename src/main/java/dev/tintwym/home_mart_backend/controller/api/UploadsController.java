package dev.tintwym.home_mart_backend.controller.api;

import dev.tintwym.home_mart_backend.service.UploadService;
import dev.tintwym.home_mart_backend.utility.ApiResponses;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/uploads")
public class UploadsController {

    private final UploadService uploadService;

    public UploadsController(UploadService uploadService) {
        this.uploadService = uploadService;
    }

    @PostMapping("/listing-image")
    public ResponseEntity<?> uploadListingImage(@RequestPart(value = "image", required = false) MultipartFile image) {
        if (image == null || image.isEmpty()) {
            return ApiResponses.unprocessable("Image is required.");
        }
        try {
            String url = uploadService.uploadListingImage(image);
            String path = url;
            if (url.startsWith("/storage/")) {
                path = url.substring("/storage/".length());
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("image_url", url);
            body.put("path", path);
            return ResponseEntity.ok(body);
        } catch (IllegalArgumentException e) {
            return ApiResponses.unprocessable(e.getMessage());
        } catch (Exception e) {
            return ApiResponses.unprocessable("Upload failed.");
        }
    }
}
