package com.justcommit.backend.product.image.application;

import java.util.List;

public interface ImageDeleter {
    void delete(List<String> keys);
}
