package com.api.products.dao;

import com.api.products.entitys.Product;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductsDAO extends JpaRepository<Product, Long> {
}
