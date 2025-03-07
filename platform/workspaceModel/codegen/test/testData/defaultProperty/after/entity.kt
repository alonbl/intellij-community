package com.intellij.workspaceModel.test.api

import com.intellij.workspaceModel.deft.api.annotations.Default
import com.intellij.workspaceModel.storage.WorkspaceEntity
import com.intellij.workspaceModel.storage.EntitySource
import com.intellij.workspaceModel.storage.GeneratedCodeApiVersion
import com.intellij.workspaceModel.storage.ModifiableWorkspaceEntity
import com.intellij.workspaceModel.storage.MutableEntityStorage
import org.jetbrains.deft.ObjBuilder
import org.jetbrains.deft.Type


interface DefaultFieldEntity : WorkspaceEntity {
  val version: Int
  val data: TestData
  val anotherVersion: Int
    @Default get() = 0
  val description: String
    @Default get() = "Default description"
  //region generated code
  //@formatter:off
  @GeneratedCodeApiVersion(1)
  interface Builder: DefaultFieldEntity, ModifiableWorkspaceEntity<DefaultFieldEntity>, ObjBuilder<DefaultFieldEntity> {
      override var version: Int
      override var entitySource: EntitySource
      override var data: TestData
      override var anotherVersion: Int
      override var description: String
  }

  companion object: Type<DefaultFieldEntity, Builder>() {
      operator fun invoke(version: Int, entitySource: EntitySource, data: TestData, init: (Builder.() -> Unit)? = null): DefaultFieldEntity {
          val builder = builder()
          builder.version = version
          builder.entitySource = entitySource
          builder.data = data
          init?.invoke(builder)
          return builder
      }
  }
  //@formatter:on
  //endregion

}
//region generated code
fun MutableEntityStorage.modifyEntity(entity: DefaultFieldEntity, modification: DefaultFieldEntity.Builder.() -> Unit) = modifyEntity(DefaultFieldEntity.Builder::class.java, entity, modification)
//endregion

data class TestData(val name: String, val description: String)