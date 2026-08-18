package org.grayray.aiassistant.user.controller;


import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.grayray.aiassistant.common.result.Result;
import org.grayray.aiassistant.user.dto.SysUserIdDTO;
import org.grayray.aiassistant.user.entity.SysUser;
import org.grayray.aiassistant.user.service.SysUserService;
import org.springframework.web.bind.annotation.*;

import java.util.List;


@RestController
@RequestMapping("/sysUser")
public class SysUserController {

    @Resource
    private SysUserService sysUserService;

    @PostMapping("/addUser")
    public Result<Void> save(@RequestBody  SysUser user){
        sysUserService.save(user);
        return Result.success();
    }

    @GetMapping("/{id}")
    public Result<SysUser> getById(@PathVariable  @Valid Long id){
        return  Result.success(sysUserService.getById(id)) ;

    }

    @GetMapping("/list")
    public Result<List<SysUser>>   getUserList(){
        return  Result.success(sysUserService.list());
    }

    @PostMapping("/update")
    public Result<Boolean> update(@RequestBody SysUser user) {
        return Result.success(sysUserService.updateById(user));
    }


    @PostMapping("/delete")
    public Result<Boolean>  delete(@RequestBody @Valid SysUserIdDTO dto){
        return  Result.success(sysUserService.removeById(dto.getId())) ;
    }





}
