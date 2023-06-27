package com.api.products.entitys;


import javax.persistence.*;

@Entity
@Table(name = "products")
public class Product {

    @Id
    @Column (name = "id")
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;
    @Column (name = "name", nullable = false, length = 50)
    private String name;

//getters and setters
    public long getId() {
        return this.id;
    }


    public void setId(long id) {
        this.id = id;
    }


    public String getName() {
        return this.name;
    }


    public void setName(String name) {
        this.name = name;
    }
}
