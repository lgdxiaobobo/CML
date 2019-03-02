A spark version about Collaborative Metric Learning for Ranking

mainly based on "Collaborative Metric Learning"

codes structure:
  src.jyb
    CMModel.scala: main-model 
    package.scala: basic function
    Param.scala: model-based parameters
    test.scala: IO for model

Assumption:
  the number of users >> the number of items
  thus, distributed users in blocks (like mllib.ALS), and store items locally (as A map)

  utilize AdaGard for adaptive adjusting study-rate