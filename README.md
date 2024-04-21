# Epiktetos (alpha)

> stoic maxim

Epiktetos is a micro-framework for shader programming.

It is meant to lower barriers to shaders programming by abstracting as much as possible of OpenGL API behind a simple interface, while at the same time preserving enough control over the lower-level parts of graphic programming.

Systems :
* Configurable shader programs
* Rendering pipeline
* State management "à la re-frame"
* Reloaded workflow

## Getting started
### Installation
Add the following dependency to your deps.edn file:
```clojure
 io.github.Joeyjoejoe/epiktetos {:git/tag "alpha-0" :git/sha "de94c8e"}
```

### Hello triangle 
> [!TIP]
> If you are new to clojure and not confortable with setting up your project by yourself, but just want to follow this tutorial : [Do this first](#setting-up-your-project)

> Every serious shader programming library should have an "Hello triangle" example, m'kay ?!
> 
> -- *hearsay*

What do you need to render your first triangle with Epiktetos ?
* a development environment
* a window
* a triangle
* a vertex shader
* a fragment shader

#### Development environment

Epiktetos provides the `epiktetos.dev` namespace which expose 4 functions `start`, `stop`, `resume` and `reset` inspired by the [reloaded workflow](http://thinkrelevance.com/blog/2013/06/04/clojure-workflow-reloaded) and implemented with [integrant](https://github.com/weavejester/integrant/tree/master) :

* `(start)`  Open the window and start the rendering loop. 
* `(stop)`   Close the window.
* `(reset)`  Reset the window, which is equivalent to runing `(stop)` then `(start)`
* `(resume)` Resume a paused rendering loop.

> [!CAUTION]
> `(start)` is a blocking function as it starts a loop. You won't be able to execute other REPL commands until the rendering loop is either stopped or paused.
>
> **In order to pause the rendering loop, press the escape key while focusing on the window.**

Add `epiktetos.dev` namespace to your `user` namespace :

```clojure
(ns user                                                    
  (:require [epiktetos.dev :refer [start stop resume reset]]
            [hello-triangle.core :as _]))                   
```

#### The window

## Setting up your project

### Files & directories hierarchy

```bash
hello-triangle/
├── deps.edn
├── env/
│   └── dev/
│       └── user.clj
├── resources/
│   ├── epiktetos.edn
│   ├── models/
│   ├── shaders/
│   └── textures/
└── src/
    └── hello_triangle/
        └── core.clj
```

### Files content
#### `deps.edn`
This file provides all necessary dependencies and create the `dev` alias you'll use later to launch your development environment.

```edn
{:paths   ["src" "resources"]                                        
 :deps    {org.clojure/clojure {:mvn/version "1.11.2"}               
           io.github.Joeyjoejoe/epiktetos {:git/tag "alpha-0" :git/sha "de94c8e"}}         
 :aliases {:dev {:extra-paths ["env/dev"]}}}
```

#### `env/dev/user.clj`
This file define the `user` namespace which is the default namespace you'll be put in when you start your development environment.

```clojure
(ns user                                                    
  (:require [epiktetos.dev :refer [start stop resume reset]]
            [hello-triangle.core :as _]))                   
```

#### `resources/epiktetos.edn`
This is Epiktetos config file. It's an empty `.edn` file for now, you'll soon complete it in the "Hello triangle" example.

```edn
{}
```

#### `src/hello_triangle/core.clj`
This is your project's main namespace where you'll implement your first triangle.

```clojure
(ns hello-triangle.core                                              
  (:require [epiktetos.core :as epik]))
```

### Start your development environment
Running the folowing command in your terminal (from the root of your project) will start a REPL where you can execute clojure code : 

```bash
clj -M:dev
```
At this point, if your project is correctly setted up and run this command, you should see that :

![REPL is runing](https://github.com/Joeyjoejoe/epiktetos/assets/338690/b40ef297-4f00-45b9-9094-fcce64a7c5c4)


> [!IMPORTANT]
> Congratulations, you are ready to start your epiktetos journey ! 
> [Go back to the "Hello triangle" example](#development-environment)


