package com.justcommit.backend.product.application;

public interface ImageUploadUrlIssuer {

    String issue(String key, String contentType, long size);
}