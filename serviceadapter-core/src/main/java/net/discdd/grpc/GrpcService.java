package net.discdd.grpc;
/*
 * Copyright (c) 2016-2023 The gRPC-Spring Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import io.grpc.BindableService;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Service;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation that marks gRPC services that should be registered with a gRPC server. If spring-boot's auto configuration
 * is used, then the server will be created automatically. This annotation should only be added to implementations of
 * {@link BindableService} (GrpcService-ImplBase).
 *
 * <p>
 * <b>Note:</b> This is a customized GrpcService annotation for DDD Grpc services.
 * </p>
 *
 * @author Michael (yidongnan@gmail.com)
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Service
@Bean
public @interface GrpcService {
}