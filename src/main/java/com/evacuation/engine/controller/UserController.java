package com.evacuation.engine.controller;

import com.evacuation.engine.model.User;
import com.evacuation.engine.service.UserService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping("/register")
    public User registerUser(@RequestBody User user) {
        return userService.registerUser(user);
    }

    @GetMapping
    public List<User> getUsers() {
        return userService.getAllUsers();
    }
}