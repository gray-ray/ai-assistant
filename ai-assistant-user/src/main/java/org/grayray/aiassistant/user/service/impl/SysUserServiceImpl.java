package org.grayray.aiassistant.user.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.grayray.aiassistant.user.entity.SysUser;
import org.grayray.aiassistant.user.mapper.SysUserMapper;
import org.grayray.aiassistant.user.service.SysUserService;
import org.springframework.stereotype.Service;

@Service
public class SysUserServiceImpl extends ServiceImpl<SysUserMapper, SysUser> implements SysUserService {
    // 基础 CRUD 全部自带，不用写！
    // 只有自定义的业务方法才在这里加

}
