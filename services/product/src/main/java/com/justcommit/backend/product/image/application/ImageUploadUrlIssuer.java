package com.justcommit.backend.product.image.application;

public interface ImageUploadUrlIssuer {

    String issue(String key, String contentType, long size);
}