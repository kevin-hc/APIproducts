package com.api.products.rest;

import com.api.products.dao.ProductsDAO;
import com.api.products.entitys.Product;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/products")
public class ProductsREST {

    @Autowired
    private ProductsDAO productsDAO;

    // Obtener todos los productos
    @GetMapping
    public ResponseEntity<List<Product>> getProducts() {
        List<Product> products = productsDAO.findAll();
        return ResponseEntity.ok(products);
    }

    // Obtener producto por ID
    @GetMapping("/{id}")
    public ResponseEntity<Product> getProductById(@PathVariable Long id) {
        Optional<Product> optionalProduct = productsDAO.findById(id);
        return optionalProduct.map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    // Crear producto
    @PostMapping
    public ResponseEntity<Product> createProduct(@RequestBody Product product) {
        Product newProduct = productsDAO.save(product);
        return ResponseEntity.status(HttpStatus.CREATED).body(newProduct);
    }

    // Borrar producto
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteProduct(@PathVariable Long id) {
        productsDAO.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    // Actualizar producto
    @PutMapping
    public ResponseEntity<Product> updateProduct(@RequestBody Product product) {
        Optional<Product> optionalProduct = productsDAO.findById(product.getId());
        if (optionalProduct.isPresent()) {
            Product updateProduct = optionalProduct.get();
            updateProduct.setName(product.getName());
            productsDAO.save(updateProduct);
            return ResponseEntity.ok(updateProduct);
        } else {
            return ResponseEntity.notFound().build();
        }
    }
}
